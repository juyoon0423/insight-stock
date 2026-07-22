-- chunks 테이블이 비어 있는 시점이라 기본값 없이 NOT NULL로 바로 추가한다.
ALTER TABLE chunks ADD COLUMN chunk_source VARCHAR(255) NOT NULL;
