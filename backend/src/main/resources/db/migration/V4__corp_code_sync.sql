-- corp_cls(법인구분)는 corpCode.xml에 없는 필드다(실측: dart-findings.md 2-11).
-- corpCode.xml만으로 채워진 신규 회사는 corp_cls를 알 수 없으므로 nullable로 완화한다.
-- 값은 이후 list API 수집(DisclosureCollectorService)이 실제 공시를 만나는 시점에 채운다.
ALTER TABLE companies ALTER COLUMN corp_cls DROP NOT NULL;

-- 10만 건 규모에서 회사명 부분 일치 검색(현재 CompanyRepository의 LIKE '%q%')이
-- B-tree로는 인덱스를 못 타므로, 부분 문자열 검색에 맞는 pg_trgm(trigram) GIN 인덱스를 쓴다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_companies_corp_name_trgm ON companies USING gin (corp_name gin_trgm_ops);

-- corpCode.xml 동기화가 corp_name/stock_code를 갱신할 때 그 시각을 추적하기 위해 추가한다
-- (disclosures.updated_at과 같은 목적 - "상태/값이 마지막으로 바뀐 시각").
ALTER TABLE companies ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
