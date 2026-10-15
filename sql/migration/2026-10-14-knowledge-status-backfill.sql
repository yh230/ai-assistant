-- 存量库升级：Kafka 批（2026-10-14）为 chat_db.knowledge_document 新增 status 列（JPA 自动加列，存量行隐式 0=PENDING）。
-- 本批前上传的文档均已完成向量化，需回填为就绪（1），否则删除任一篇触发重建时会丢失旧文档向量。
-- 仅存量库升级执行一次；全新库请勿执行（会误置"处理中"文档为就绪）。
UPDATE knowledge_document SET status = 1 WHERE status = 0;
