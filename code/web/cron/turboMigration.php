<?php
/**
 * Background process launcher for Turbo Migration.
 * Launches the Java TurboMigrationMain as a subprocess.
 *
 * Called by SystemUtils::startBackgroundProcess() from the admin UI or API.
 *
 * Usage (called automatically):
 *   php turboMigration.php <serverName> <backgroundProcessId> [workerThreads] [batchSize] [profileName]
 */

require_once __DIR__ . '/../bootstrap.php';
require_once __DIR__ . '/../bootstrap_aspen.php';
require_once ROOT_DIR . '/sys/Administration/BackgroundProcess.php';

$backgroundProcess = null;
if ($argc > 2) {
	$backgroundProcessId = $argv[2];
	$backgroundProcess = new BackgroundProcess();
	$backgroundProcess->id = $backgroundProcessId;
	if (!$backgroundProcess->find(true)) {
		echo("Could not find the specified background process\n");
		die();
	} elseif (!$backgroundProcess->isRunning) {
		$backgroundProcess->addNote('Error: attempted to restart previously completed background process');
		die();
	}
}

$workerThreads = ($argc > 3 && is_numeric($argv[3])) ? intval($argv[3]) : 4;
$batchSize = ($argc > 4 && is_numeric($argv[4])) ? intval($argv[4]) : 500;
$profileName = ($argc > 5) ? $argv[5] : 'ils';

logMessage("Starting Turbo Migration: $workerThreads workers, batch=$batchSize, profile=$profileName", $backgroundProcess);

// Stop all other indexers and disable auto-restart cron
logMessage("Stopping background indexers to dedicate resources...", $backgroundProcess);
stopBackgroundIndexers($backgroundProcess);

// Locate JARs
$aspenRoot = realpath(__DIR__ . '/../../..');
$parallelJar = $aspenRoot . '/code/parallel_reindexer/parallel_reindexer.jar';
$reindexerJar = $aspenRoot . '/code/reindexer/reindexer.jar';
$normalizerJar = $aspenRoot . '/code/reindexer/lib/normalizer.jar';
$sharedLibDir = $aspenRoot . '/code/java_shared_libraries';

if (!file_exists($parallelJar)) {
	finish("Error: parallel_reindexer.jar not found at $parallelJar", $backgroundProcess);
}

// Build classpath
$classpath = $parallelJar . ':' . $reindexerJar;
if (file_exists($normalizerJar)) $classpath .= ':' . $normalizerJar;
foreach (glob($sharedLibDir . '/*.jar') as $jar) $classpath .= ':' . $jar;

$solrLocations = [
	$aspenRoot . '/sites/default/solr-8.11.2/dist',
	'/usr/local/aspen-discovery/sites/default/solr-8.11.2/dist',
];
foreach ($solrLocations as $loc) {
	if (is_dir($loc)) {
		foreach (glob($loc . '/*.jar') as $jar) $classpath .= ':' . $jar;
		foreach (glob($loc . '/solrj-lib/*.jar') as $jar) $classpath .= ':' . $jar;
		break;
	}
}

global $serverName;
$javaHeap = $workerThreads >= 8 ? '-Xmx4g -Xms2g' : '-Xmx2g -Xms1g';

$javaCmd = "java $javaHeap -cp " . escapeshellarg($classpath)
	. ' com.bws.aspen.parallel_reindexer.TurboMigrationMain'
	. ' ' . escapeshellarg($serverName)
	. ' ' . escapeshellarg($workerThreads)
	. ' ' . escapeshellarg($batchSize)
	. ' ' . escapeshellarg($profileName);

logMessage("Launching: $javaCmd", $backgroundProcess);

$descriptors = [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']];
$process = proc_open($javaCmd, $descriptors, $pipes, $aspenRoot . '/code/parallel_reindexer', []);

if (!is_resource($process)) {
	finish("Error: Failed to start Java process", $backgroundProcess);
}

fclose($pipes[0]);
stream_set_blocking($pipes[1], false);
stream_set_blocking($pipes[2], false);

$lastUpdateTime = time();
$outputBuffer = '';
$lineCount = 0;

while (true) {
	$stdout = fgets($pipes[1]);
	$stderr = fgets($pipes[2]);
	$hasOutput = false;

	if ($stdout !== false && $stdout !== '') { $outputBuffer .= $stdout; $hasOutput = true; $lineCount++; }
	if ($stderr !== false && $stderr !== '') { $outputBuffer .= '[ERR] ' . $stderr; $hasOutput = true; $lineCount++; }

	$now = time();
	if (($now - $lastUpdateTime >= 10 || $lineCount >= 50) && !empty($outputBuffer)) {
		logMessage(trim($outputBuffer), $backgroundProcess);
		$outputBuffer = '';
		$lineCount = 0;
		$lastUpdateTime = $now;
	}

	$status = proc_get_status($process);
	if (!$status['running']) {
		while (($line = fgets($pipes[1])) !== false) $outputBuffer .= $line;
		while (($line = fgets($pipes[2])) !== false) $outputBuffer .= '[ERR] ' . $line;
		if (!empty($outputBuffer)) logMessage(trim($outputBuffer), $backgroundProcess);
		break;
	}

	if (!$hasOutput) usleep(100000);
}

fclose($pipes[1]);
fclose($pipes[2]);
$exitCode = proc_close($process);

// Re-enable background indexers
logMessage("Re-enabling background indexers...", $backgroundProcess);
startBackgroundIndexers($backgroundProcess);

if ($exitCode === 0) {
	finish("Turbo Migration completed successfully. Run Turbo Reindex to index into Solr.", $backgroundProcess);
} else {
	finish("Turbo Migration finished with exit code $exitCode", $backgroundProcess);
}

function logMessage(string $message, ?BackgroundProcess $backgroundProcess): void {
	echo $message . "\n";
	if ($backgroundProcess !== null) {
		$backgroundProcess->addNote(date('H:i:s') . ' - ' . $message);
	}
}

function finish(string $message, ?BackgroundProcess $backgroundProcess): void {
	echo $message . "\n";
	if ($backgroundProcess !== null) {
		$backgroundProcess->endProcess($message);
	}
	die();
}

function stopBackgroundIndexers(?BackgroundProcess $backgroundProcess): void {
	// Disable checkBackgroundProcesses cron entry
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
			exec("crontab $tmpFile 2>&1");
			unlink($tmpFile);
			logMessage("Disabled indexer auto-restart cron", $backgroundProcess);
		}
	}

	// Kill all Java indexer processes (except turbo tools)
	exec("ps -eo pid,args 2>/dev/null | grep 'java.*\\.jar' | grep -v grep | grep -v TurboMigration | grep -v ParallelReindex", $processes);
	$killed = 0;
	foreach ($processes as $proc) {
		$proc = trim($proc);
		if (preg_match('/^(\d+)\s+/', $proc, $m)) {
			exec("kill " . $m[1] . " 2>/dev/null");
			$killed++;
		}
	}
	if ($killed > 0) {
		logMessage("Stopped $killed background indexer process(es)", $backgroundProcess);
	}
	// Brief pause to let processes exit cleanly
	sleep(2);
}

function startBackgroundIndexers(?BackgroundProcess $backgroundProcess): void {
	// Re-enable checkBackgroundProcesses cron entry
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
			exec("crontab $tmpFile 2>&1");
			unlink($tmpFile);
			logMessage("Re-enabled indexer auto-restart cron", $backgroundProcess);
		}
	}

	// Trigger checkBackgroundProcesses to restart indexers now
	global $serverName;
	$checkScript = '/usr/local/aspen-discovery/docker/files/cron/checkBackgroundProcessesDocker.php';
	if (file_exists($checkScript)) {
		exec("sudo -u www-data php $checkScript $serverName > /dev/null 2>&1 &");
		logMessage("Triggered indexer restart", $backgroundProcess);
	}
}
