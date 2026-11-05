INSERT INTO data_platform.ads_pipeline_health
SELECT now('Asia/Shanghai') AS metric_time, source_table, sum(rows_ingested) AS rows_ingested,
       dateDiff('second', max(_ingested_at), now('Asia/Shanghai')) AS lag_seconds,
       now('Asia/Shanghai') AS updated_at
FROM data_platform.ops_ingest_audit FINAL
WHERE toDate(_ingested_at, 'Asia/Shanghai') = '{{ds}}'
GROUP BY source_table;
