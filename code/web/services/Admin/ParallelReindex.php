<?php

require_once ROOT_DIR . '/services/Admin/Admin.php';

class Admin_ParallelReindex extends Admin_Admin {

	function launch(): void {
		global $interface;

		$aspenRoot = realpath(ROOT_DIR . '/../..');
		$jarPath = $aspenRoot . '/code/parallel_reindexer/parallel_reindexer.jar';
		$jarExists = file_exists($jarPath);
		$interface->assign('jarExists', $jarExists);

		require_once ROOT_DIR . '/sys/Administration/BackgroundProcess.php';

		// ---- Turbo Reindex status ----
		$isReindexRunning = false;
		$reindexProcess = null;
		$bgProcess = new BackgroundProcess();
		$bgProcess->name = 'parallelReindex';
		$bgProcess->isRunning = 1;
		if ($bgProcess->find(true)) {
			$isReindexRunning = true;
			$reindexProcess = ['id' => $bgProcess->id, 'startTime' => $bgProcess->startTime];
		}
		$interface->assign('isReindexRunning', $isReindexRunning);
		$interface->assign('reindexProcess', $reindexProcess);

		// ---- Turbo Migration status ----
		$isMigrationRunning = false;
		$migrationProcess = null;
		$bgProcess2 = new BackgroundProcess();
		$bgProcess2->name = 'turboMigration';
		$bgProcess2->isRunning = 1;
		if ($bgProcess2->find(true)) {
			$isMigrationRunning = true;
			$migrationProcess = ['id' => $bgProcess2->id, 'startTime' => $bgProcess2->startTime];
		}
		$interface->assign('isMigrationRunning', $isMigrationRunning);
		$interface->assign('migrationProcess', $migrationProcess);

		// ---- Counts ----
		$totalWorks = 0;
		try {
			require_once ROOT_DIR . '/sys/Grouping/GroupedWork.php';
			$groupedWork = new GroupedWork();
			$totalWorks = $groupedWork->count();
		} catch (Exception $e) { }
		$interface->assign('totalWorks', number_format($totalWorks));

		// Count ils_records
		$totalIlsRecords = 0;
		try {
			global $aspen_db;
			if ($aspen_db) {
				$result = $aspen_db->query("SELECT COUNT(*) as cnt FROM ils_records WHERE deleted = 0");
				if ($row = $result->fetch(PDO::FETCH_ASSOC)) {
					$totalIlsRecords = $row['cnt'];
				}
			}
		} catch (Exception $e) { }
		$interface->assign('totalIlsRecords', number_format($totalIlsRecords));

		// Check Koha connection
		$kohaConnected = false;
		$kohaBibCount = 0;
		try {
			require_once ROOT_DIR . '/sys/Account/AccountProfile.php';
			$accountProfile = new AccountProfile();
			$accountProfile->ils = 'koha';
			if ($accountProfile->find(true) && !empty($accountProfile->databaseHost)) {
				$port = empty($accountProfile->databasePort) ? 3306 : $accountProfile->databasePort;
				$testConn = @mysqli_connect(
					$accountProfile->databaseHost,
					$accountProfile->databaseUser,
					$accountProfile->databasePassword,
					$accountProfile->databaseName,
					$port
				);
				if ($testConn) {
					$kohaConnected = true;
					$result = mysqli_query($testConn, "SELECT COUNT(*) as cnt FROM biblio_metadata");
					if ($row = mysqli_fetch_assoc($result)) {
						$kohaBibCount = $row['cnt'];
					}
					mysqli_close($testConn);
				}
			}
		} catch (Exception $e) { }
		$interface->assign('kohaConnected', $kohaConnected);
		$interface->assign('kohaBibCount', number_format($kohaBibCount));

		// Recent reindex log entries
		$recentLogs = [];
		try {
			require_once ROOT_DIR . '/sys/Indexing/ReindexLogEntry.php';
			$logEntry = new ReindexLogEntry();
			$logEntry->orderBy('id desc');
			$logEntry->limit(0, 5);
			$logEntry->find();
			while ($logEntry->fetch()) {
				$recentLogs[] = [
					'id' => $logEntry->id,
					'startTime' => $logEntry->startTime,
					'endTime' => $logEntry->endTime,
					'numWorksProcessed' => $logEntry->numWorksProcessed,
					'numErrors' => $logEntry->numErrors,
				];
			}
		} catch (Exception $e) { }
		$interface->assign('recentLogs', $recentLogs);

		// ---- Handle Turbo Reindex submission ----
		if (isset($_REQUEST['startReindex']) && $jarExists && !$isReindexRunning) {
			$workerThreads = 4;
			if (isset($_REQUEST['workerThreads']) && is_numeric($_REQUEST['workerThreads'])) {
				$workerThreads = max(1, min(16, intval($_REQUEST['workerThreads'])));
			}
			$mode = 'fullNoClear';
			if (isset($_REQUEST['clearIndex']) && $_REQUEST['clearIndex'] == '1') {
				$mode = 'full';
			}
			require_once ROOT_DIR . '/sys/Utils/SystemUtils.php';
			$result = SystemUtils::startBackgroundProcess('parallelReindex', [$workerThreads, $mode]);
			$interface->assign('reindexResults', $result);
			if (!empty($result['success'])) {
				$interface->assign('isReindexRunning', true);
			}
		}

		// ---- Handle Turbo Migration submission ----
		if (isset($_REQUEST['startMigration']) && $jarExists && !$isMigrationRunning && $kohaConnected) {
			$migWorkers = 4;
			if (isset($_REQUEST['migWorkerThreads']) && is_numeric($_REQUEST['migWorkerThreads'])) {
				$migWorkers = max(1, min(16, intval($_REQUEST['migWorkerThreads'])));
			}
			$batchSize = 500;
			if (isset($_REQUEST['batchSize']) && is_numeric($_REQUEST['batchSize'])) {
				$batchSize = max(100, min(5000, intval($_REQUEST['batchSize'])));
			}
			$profileName = 'ils';
			if (isset($_REQUEST['profileName']) && !empty($_REQUEST['profileName'])) {
				$profileName = preg_replace('/[^a-zA-Z0-9_-]/', '', $_REQUEST['profileName']);
			}
			require_once ROOT_DIR . '/sys/Utils/SystemUtils.php';
			$result = SystemUtils::startBackgroundProcess('turboMigration', [$migWorkers, $batchSize, $profileName]);
			$interface->assign('migrationResults', $result);
			if (!empty($result['success'])) {
				$interface->assign('isMigrationRunning', true);
			}
		}

		$this->display('parallelReindex.tpl', 'Turbo Tools');
	}

	function getBreadcrumbs(): array {
		$breadcrumbs = [];
		$breadcrumbs[] = new Breadcrumb('/Admin/Home', 'Administration Home');
		$breadcrumbs[] = new Breadcrumb('/Admin/Home#system_admin', 'System Administration');
		$breadcrumbs[] = new Breadcrumb('', 'Turbo Tools');
		return $breadcrumbs;
	}

	function canView(): bool {
		return UserAccount::userHasPermission('Perform System Maintenance');
	}

	function getActiveAdminSection(): string {
		return 'system_admin';
	}
}
