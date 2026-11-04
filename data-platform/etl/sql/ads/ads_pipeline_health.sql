INSERT INTO data_platform.ads_pipeline_health
SELECT now() AS metric_time, source_table, sum(rows_ingested) AS rows_ingested,
       dateDiff('second', max(_ingested_at), now()) AS lag_seconds, now() AS updated_at
FROM data_platform.ops_ingest_audit FINAL
WHERE toDate(_ingested_at) = '{{ds}}'
GROUP BY source_table;
