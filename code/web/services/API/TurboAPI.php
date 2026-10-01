<?php

require_once ROOT_DIR . '/services/API/AbstractAPI.php';

/**
 * Turbo Tools API — Standalone API controller for Cerulean and external integrations.
 *
 * This is a drop-in file: place it in services/API/ and it's automatically routable
 * at /API/TurboAPI?method=<methodName>. No core Aspen code modifications required.
 *
 * Endpoints:
 *   - getTurboCounts        : Pre-flight counts from Koha + Aspen
 *   - startTurboMigration   : Bulk extract Koha → Aspen DB
 *   - startTurboReindex     : Parallel Solr indexing
 *   - getTurboStatus        : Poll background process progress
 *   - stopAspenIndexers     : Kill indexers + disable auto-restart cron
 *   - startAspenIndexers    : Re-enable auto-restart cron
 *
 * Authentication: IP-based (configure in Admin → IP Addresses → Allow API Access)
 *
 * @author BWS (ByWater Solutions)
 */
class API_TurboAPI extends AbstractAPI {

	function launch(): void {
		$method = (isset($_GET['method']) && !is_array($_GET['method'])) ? $_GET['method'] : '';

		header('Content-type: application/json');
		header('Cache-Control: no-cache, must-revalidate');
		header('Expires: Mon, 26 Jul 1997 05:00:00 GMT');

		$allowedMethods = [
			'getTurboCounts',
			'startTurboMigration',
			'startTurboReindex',
			'getTurboStatus',
			'stopAspenIndexers',
			'startAspenIndexers',
		];

		if (IPAddress::allowAPIAccessForClientIP()) {
			if (in_array($method, $allowedMethods) && method_exists($this, $method)) {
				$result = ['result' => $this->$method()];
				$output = json_encode($result);
				require_once ROOT_DIR . '/sys/SystemLogging/APIUsage.php';
				APIUsage::incrementStat('TurboAPI', $method);
			} else {
				$output = json_encode(['error' => 'invalid_method']);
			}
			echo $output;
		} elseif (isset($_SERVER['PHP_AUTH_USER'])) {
			if ($this->grantTokenAccess()) {
				if (in_array($method, $allowedMethods) && method_exists($this, $method)) {
					$result = ['result' => $this->$method()];
					$output = json_encode($result);
					require_once ROOT_DIR . '/sys/SystemLogging/APIUsage.php';
					APIUsage::incrementStat('TurboAPI', $method);
				} else {
					$output = json_encode(['error' => 'invalid_method']);
				}
				echo $output;
			} else {
				header('HTTP/1.0 401 Unauthorized');
				echo json_encode(['error' => 'unauthorized_access']);
			}
		} else {
			$this->forbidAPIAccess();
		}
	}

	// ====================================================================
	// Endpoints
	// ====================================================================

	/**
	 * Start a Turbo Migration (bulk extract from Koha → Aspen DB).
	 * @noinspection PhpUnused
	 */
	public function startTurboMigration(): array {
		require_once ROOT_DIR . '/sys/Administration/BackgroundProcess.php';
		$bgProcess = new BackgroundProcess();
		$bgProcess->name = 'turboMigration';
		$bgProcess->isRunning = 1;
		if ($bgProcess->find(true)) {
			return [
				'success' => false,
				'message' => 'Turbo Migration is already running',
				'backgroundProcessId' => $bgProcess->id,
				'status' => 'running',
			];
		}

		$workerThreads = isset($_REQUEST['workerThreads']) ? max(1, min(16, intval($_REQUEST['workerThreads']))) : 4;
		$batchSize = isset($_REQUEST['batchSize']) ? max(100, min(5000, intval($_REQUEST['batchSize']))) : 500;
		$profileName = isset($_REQUEST['profileName']) ? preg_replace('/[^a-zA-Z0-9_-]/', '', $_REQUEST['profileName']) : 'ils';

		require_once ROOT_DIR . '/sys/Utils/SystemUtils.php';
		$result = SystemUtils::startBackgroundProcess('turboMigration', [$workerThreads, $batchSize, $profileName]);

		if (!empty($result['success'])) {
			return [
				'success' => true,
				'message' => "Turbo Migration started with $workerThreads workers, batch=$batchSize",
				'backgroundProcessId' => $result['backgroundProcessId'],
				'status' => 'started',
			];
		}
		return ['success' => false, 'message' => $result['message'] ?? 'Failed to start'];
	}

	/**
	 * Start a Turbo Reindex (parallel Solr indexing of grouped works).
	 * @noinspection PhpUnused
	 */
	public function startTurboReindex(): array {
		require_once ROOT_DIR . '/sys/Administration/BackgroundProcess.php';
		$bgProcess = new BackgroundProcess();
		$bgProcess->name = 'parallelReindex';
		$bgProcess->isRunning = 1;
		if ($bgProcess->find(true)) {
			return [
				'success' => false,
				'message' => 'Turbo Reindex is already running',
				'backgroundProcessId' => $bgProcess->id,
				'status' => 'running',
			];
		}

		$workerThreads = isset($_REQUEST['workerThreads']) ? max(1, min(16, intval($_REQUEST['workerThreads']))) : 4;
		$mode = (!empty($_REQUEST['clearIndex']) && $_REQUEST['clearIndex'] == '1') ? 'full' : 'fullNoClear';

		require_once ROOT_DIR . '/sys/Utils/SystemUtils.php';
		$result = SystemUtils::startBackgroundProcess('parallelReindex', [$workerThreads, $mode]);

		if (!empty($result['success'])) {
			return [
				'success' => true,
				'message' => "Turbo Reindex started with $workerThreads workers, mode=$mode",
				'backgroundProcessId' => $result['backgroundProcessId'],
				'status' => 'started',
			];
		}
		return ['success' => false, 'message' => $result['message'] ?? 'Failed to start'];
	}

	/**
	 * Get the status of a running or completed Turbo operation.
	 * @noinspection PhpUnused
	 */
	public function getTurboStatus(): array {
		$processId = $_REQUEST['backgroundProcessId'] ?? null;
		if (empty($processId)) {
			return ['success' => false, 'message' => 'backgroundProcessId is required'];
		}

		require_once ROOT_DIR . '/sys/Administration/BackgroundProcess.php';
		$bgProcess = new BackgroundProcess();
		$bgProcess->id = intval($processId);
		if (!$bgProcess->find(true)) {
			return ['success' => false, 'message' => 'Background process not found'];
		}

		$status = $bgProcess->isRunning ? 'running' : 'completed';
		$elapsed = $bgProcess->isRunning ? (time() - $bgProcess->startTime) : ($bgProcess->endTime - $bgProcess->startTime);

		return [
			'success' => true,
			'backgroundProcessId' => $bgProcess->id,
			'name' => $bgProcess->name,
			'status' => $status,
			'isRunning' => (bool)$bgProcess->isRunning,
			'startTime' => $bgProcess->startTime,
			'endTime' => $bgProcess->endTime,
			'elapsedSeconds' => $elapsed,
			'notes' => $bgProcess->notes,
		];
	}

	/**
	 * Get migration-relevant counts from both Koha and Aspen databases.
	 * @noinspection PhpUnused
	 */
	public function getTurboCounts(): array {
		$counts = [
			'success' => true,
			'aspen' => [
				'ilsRecords' => 0,
				'groupedWorks' => 0,
			],
			'koha' => [
				'connected' => false,
				'bibs' => 0,
				'items' => 0,
			],
		];

		global $aspen_db;
		if ($aspen_db) {
			try {
				$result = $aspen_db->query("SELECT COUNT(*) as cnt FROM ils_records WHERE deleted = 0");
				if ($row = $result->fetch(PDO::FETCH_ASSOC)) {
					$counts['aspen']['ilsRecords'] = (int)$row['cnt'];
				}
			} catch (Exception $e) { }

			try {
				require_once ROOT_DIR . '/sys/Grouping/GroupedWork.php';
				$gw = new GroupedWork();
				$counts['aspen']['groupedWorks'] = $gw->count();
			} catch (Exception $e) { }
		}

		try {
			require_once ROOT_DIR . '/sys/Account/AccountProfile.php';
			$accountProfile = new AccountProfile();
			$accountProfile->ils = 'koha';
			if ($accountProfile->find(true) && !empty($accountProfile->databaseHost)) {
				$port = empty($accountProfile->databasePort) ? 3306 : $accountProfile->databasePort;
				$kohaConn = @mysqli_connect(
					$accountProfile->databaseHost,
					$accountProfile->databaseUser,
					$accountProfile->databasePassword,
					$accountProfile->databaseName,
					$port
				);
				if ($kohaConn) {
					$counts['koha']['connected'] = true;
					$result = mysqli_query($kohaConn, "SELECT COUNT(*) as cnt FROM biblio_metadata");
					if ($row = mysqli_fetch_assoc($result)) {
						$counts['koha']['bibs'] = (int)$row['cnt'];
					}
					$result = mysqli_query($kohaConn, "SELECT COUNT(*) as cnt FROM items");
					if ($row = mysqli_fetch_assoc($result)) {
						$counts['koha']['items'] = (int)$row['cnt'];
					}
					mysqli_close($kohaConn);
				}
			}
		} catch (Exception $e) { }

		return $counts;
	}

	/**
	 * Stop all Aspen background indexer processes and disable the cron that restarts them.
	 * @noinspection PhpUnused
	 */
	public function stopAspenIndexers(): array {
		$stopped = [];
		$errors = [];

		// Disable the checkBackgroundProcesses cron entry
		$cronDisabled = false;
		exec("crontab -l 2>/dev/null", $cronLines, $rc);
		if ($rc === 0) {
			$modified = false;
			foreach ($cronLines as &$line) {
				if (strpos($line, 'checkBackgroundProcesses') !== false && strpos($line, '# TURBO_DISABLED') === false) {
					$line = '# TURBO_DISABLED ' . $line;
					$modified = true;
				}
			}
			unset($line);
			if ($modified) {
				$tmpFile = tempnam(sys_get_temp_dir(), 'cron');
				file_put_contents($tmpFile, implode("\n", $cronLines) . "\n");
				exec("crontab $tmpFile 2>&1", $out, $rc2);
				unlink($tmpFile);
				$cronDisabled = ($rc2 === 0);
			} else {
				$cronDisabled = true;
			}
		}
		$stopped[] = $cronDisabled ? 'Cron auto-restart disabled' : 'Warning: could not disable cron';

		// Kill all Java indexer processes (except turbo tools)
		exec("ps -eo pid,args 2>/dev/null | grep 'java.*\\.jar' | grep -v grep | grep -v TurboMigration | grep -v ParallelReindex", $processes);
		foreach ($processes as $proc) {
			$proc = trim($proc);
			if (preg_match('/^(\d+)\s+/', $proc, $m)) {
				$pid = $m[1];
				$jarName = 'unknown';
				if (preg_match('/java.*?-jar\s+(\S+\.jar)/', $proc, $jm)) {
					$jarName = basename($jm[1]);
				}
				exec("kill $pid 2>&1", $killOut, $killRc);
				if ($killRc === 0) {
					$stopped[] = "Killed $jarName (PID $pid)";
				} else {
					$errors[] = "Failed to kill $jarName (PID $pid)";
				}
			}
		}

		if (empty($processes)) {
			$stopped[] = 'No indexer processes were running';
		}

		return [
			'success' => true,
			'message' => 'Indexers stopped and auto-restart disabled',
			'stopped' => $stopped,
			'errors' => $errors,
		];
	}

	/**
	 * Re-enable the Aspen background indexer auto-restart cron.
	 * @noinspection PhpUnused
	 */
	public function startAspenIndexers(): array {
		$cronEnabled = false;
		exec("crontab -l 2>/dev/null", $cronLines, $rc);
		if ($rc === 0) {
			$modified = false;
			foreach ($cronLines as &$line) {
				if (strpos($line, '# TURBO_DISABLED ') !== false) {
					$line = str_replace('# TURBO_DISABLED ', '', $line);
					$modified = true;
				}
			}
			unset($line);
			if ($modified) {
				$tmpFile = tempnam(sys_get_temp_dir(), 'cron');
				file_put_contents($tmpFile, implode("\n", $cronLines) . "\n");
				exec("crontab $tmpFile 2>&1", $out, $rc2);
				unlink($tmpFile);
				$cronEnabled = ($rc2 === 0);
			} else {
				$cronEnabled = true;
			}
		}

		global $serverName;
		$checkScript = '/usr/local/aspen-discovery/docker/files/cron/checkBackgroundProcessesDocker.php';
		if (file_exists($checkScript)) {
			exec("sudo -u www-data php $checkScript $serverName > /dev/null 2>&1 &");
		}

		return [
			'success' => true,
			'message' => $cronEnabled
				? 'Auto-restart re-enabled, indexers will start within 5 minutes'
				: 'Warning: could not re-enable cron, but attempted to start indexers',
		];
	}

	function getBreadcrumbs(): array {
		return [];
	}
}
