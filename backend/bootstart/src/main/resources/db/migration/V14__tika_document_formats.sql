ALTER TABLE rag_document_revision DROP CONSTRAINT rag_document_revision_format_check;
ALTER TABLE rag_document_revision ADD CONSTRAINT rag_document_revision_format_check
    CHECK (format IN ('text', 'markdown', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
                      'odt', 'ods', 'odp', 'rtf', 'html', 'csv'));

COMMENT ON COLUMN rag_document_revision.format IS '文档解析格式：TXT、Markdown、PDF、Office、OpenDocument、RTF、HTML 或 CSV';
COMMENT ON COLUMN rag_document_revision.parsed_text IS '解析器输出的正文检查点，后续分块及来源码点位置均基于此文本';
