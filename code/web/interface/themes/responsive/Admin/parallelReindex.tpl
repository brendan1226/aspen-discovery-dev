{strip}
<div id="main-content" class="col-xs-12">
	<div class="row">
		<div class="col-xs-12">
			<h1 id="pageTitle">{translate text='Turbo Tools' isAdminFacing=true}</h1>
		</div>
	</div>

	{if !$jarExists}
		<div class="row">
			<div class="col-xs-12">
				<div class="alert alert-danger">
					<strong>{translate text='Plugin Not Installed' isAdminFacing=true}</strong><br/>
					{translate text='The parallel_reindexer.jar was not found. Please build and deploy the plugin first.' isAdminFacing=true}
				</div>
			</div>
		</div>
	{/if}

	{* ============================================================ *}
	{* SECTION 1: TURBO MIGRATION (Koha → Aspen DB)                 *}
	{* ============================================================ *}
	<div class="panel panel-primary">
		<div class="panel-heading">
			<h3 class="panel-title">{translate text='Step 1: Turbo Migration (Koha → Aspen DB)' isAdminFacing=true}</h3>
		</div>
		<div class="panel-body">
			<p>{translate text='Bulk extracts all records from Koha and loads them into Aspen database using parallel threads. Does NOT touch Solr — use Turbo Reindex (below) after this completes.' isAdminFacing=true}</p>

			<div class="row" style="margin-bottom:15px;">
				<div class="col-sm-4">
					<strong>{translate text='Koha Connection:' isAdminFacing=true}</strong>
					{if $kohaConnected}
						<span class="label label-success">{translate text='Connected' isAdminFacing=true}</span>
					{else}
						<span class="label label-danger">{translate text='Not Connected' isAdminFacing=true}</span>
					{/if}
				</div>
				<div class="col-sm-4">
					<strong>{translate text='Koha Bibs:' isAdminFacing=true}</strong> {$kohaBibCount}
				</div>
				<div class="col-sm-4">
					<strong>{translate text='Aspen ILS Records:' isAdminFacing=true}</strong> {$totalIlsRecords}
				</div>
			</div>

			{if isset($migrationResults)}
				<div class="alert {if !empty($migrationResults.success)}alert-success{else}alert-danger{/if}">
					{$migrationResults.message}
					{if !empty($migrationResults.backgroundProcessId)}
						<br/><a href="/Admin/BackgroundProcesses?objectAction=edit&id={$migrationResults.backgroundProcessId}">{translate text='View Progress' isAdminFacing=true}</a>
					{/if}
				</div>
			{/if}

			{if $isMigrationRunning}
				<div class="alert alert-warning">
					<strong>{translate text='Turbo Migration is currently running' isAdminFacing=true}</strong>
					{if !empty($migrationProcess)}
						<br/><a href="/Admin/BackgroundProcesses?objectAction=edit&id={$migrationProcess.id}" class="btn btn-sm btn-default" style="margin-top:8px;">{translate text='View Progress' isAdminFacing=true}</a>
					{/if}
				</div>
			{elseif $kohaConnected && $jarExists}
				<form method="post" role="form" style="margin-top:10px;">
					<div class="form-inline">
						<div class="form-group" style="margin-right:15px;">
							<label for="migWorkerThreads">{translate text='Workers:' isAdminFacing=true}</label>
							<select name="migWorkerThreads" id="migWorkerThreads" class="form-control" style="width:auto;">
								<option value="2">2</option>
								<option value="4" selected>4</option>
								<option value="6">6</option>
								<option value="8">8</option>
							</select>
						</div>
						<div class="form-group" style="margin-right:15px;">
							<label for="batchSize">{translate text='Batch:' isAdminFacing=true}</label>
							<select name="batchSize" id="batchSize" class="form-control" style="width:auto;">
								<option value="250">250</option>
								<option value="500" selected>500</option>
								<option value="1000">1000</option>
							</select>
						</div>
						<button type="submit" name="startMigration" value="1" class="btn btn-primary"
							onclick="return confirm('{translate text='Start Turbo Migration? This will bulk-extract all records from Koha into Aspen.' isAdminFacing=true inAttribute=true}');">
							{translate text='Start Turbo Migration' isAdminFacing=true}
						</button>
					</div>
				</form>
			{elseif !$kohaConnected}
				<div class="alert alert-info">
					{translate text='Configure the Koha database connection in Account Profiles before using Turbo Migration.' isAdminFacing=true}
				</div>
			{/if}
		</div>
	</div>

	{* ============================================================ *}
	{* SECTION 2: TURBO REINDEX (Aspen DB → Solr)                   *}
	{* ============================================================ *}
	<div class="panel panel-success">
		<div class="panel-heading">
			<h3 class="panel-title">{translate text='Step 2: Turbo Reindex (Aspen DB → Solr)' isAdminFacing=true}</h3>
		</div>
		<div class="panel-body">
			<p>{translate text='Re-indexes all grouped works already in Aspen using parallel threads. Works with any ILS. The normal nightly reindex continues to run separately.' isAdminFacing=true}</p>

			<div class="row" style="margin-bottom:15px;">
				<div class="col-sm-6">
					<strong>{translate text='Grouped Works in Database:' isAdminFacing=true}</strong> {$totalWorks}
				</div>
			</div>

			{if isset($reindexResults)}
				<div class="alert {if !empty($reindexResults.success)}alert-success{else}alert-danger{/if}">
					{$reindexResults.message}
					{if !empty($reindexResults.backgroundProcessId)}
						<br/><a href="/Admin/BackgroundProcesses?objectAction=edit&id={$reindexResults.backgroundProcessId}">{translate text='View Progress' isAdminFacing=true}</a>
					{/if}
				</div>
			{/if}

			{if $isReindexRunning}
				<div class="alert alert-warning">
					<strong>{translate text='Turbo Reindex is currently running' isAdminFacing=true}</strong>
					{if !empty($reindexProcess)}
						<br/><a href="/Admin/BackgroundProcesses?objectAction=edit&id={$reindexProcess.id}" class="btn btn-sm btn-default" style="margin-top:8px;">{translate text='View Progress' isAdminFacing=true}</a>
					{/if}
				</div>
			{elseif $jarExists}
				<form method="post" role="form" style="margin-top:10px;">
					<div class="form-inline">
						<div class="form-group" style="margin-right:15px;">
							<label for="workerThreads">{translate text='Workers:' isAdminFacing=true}</label>
							<select name="workerThreads" id="workerThreads" class="form-control" style="width:auto;">
								<option value="2">2</option>
								<option value="4" selected>4</option>
								<option value="6">6</option>
								<option value="8">8</option>
								<option value="12">12</option>
							</select>
						</div>
						<div class="form-group" style="margin-right:15px;">
							<label><input type="checkbox" name="clearIndex" value="1" /> {translate text='Clear index first' isAdminFacing=true}</label>
						</div>
						<button type="submit" name="startReindex" value="1" class="btn btn-success"
							onclick="return confirm('{translate text='Start Turbo Reindex?' isAdminFacing=true inAttribute=true}');">
							{translate text='Start Turbo Reindex' isAdminFacing=true}
						</button>
					</div>
				</form>
			{/if}
		</div>
	</div>

	{* ============================================================ *}
	{* SECTION 3: Recent History                                     *}
	{* ============================================================ *}
	{if !empty($recentLogs)}
		<div class="panel panel-default">
			<div class="panel-heading">
				<h3 class="panel-title">{translate text='Recent Reindex History' isAdminFacing=true}</h3>
			</div>
			<div class="panel-body">
				<table class="table table-striped table-condensed">
					<thead><tr>
						<th>{translate text='ID' isAdminFacing=true}</th>
						<th>{translate text='Started' isAdminFacing=true}</th>
						<th>{translate text='Finished' isAdminFacing=true}</th>
						<th>{translate text='Works' isAdminFacing=true}</th>
						<th>{translate text='Errors' isAdminFacing=true}</th>
					</tr></thead>
					<tbody>
						{foreach from=$recentLogs item=log}
							<tr>
								<td>{$log.id}</td>
								<td>{if $log.startTime}{$log.startTime|date_format:"%Y-%m-%d %H:%M"}{/if}</td>
								<td>{if $log.endTime}{$log.endTime|date_format:"%Y-%m-%d %H:%M"}{else}<em>Running...</em>{/if}</td>
								<td>{$log.numWorksProcessed|number_format}</td>
								<td>{$log.numErrors}</td>
							</tr>
						{/foreach}
					</tbody>
				</table>
				<a href="/Admin/ReindexLog">{translate text='View Full Reindex Log' isAdminFacing=true}</a>
			</div>
		</div>
	{/if}
</div>
{/strip}
