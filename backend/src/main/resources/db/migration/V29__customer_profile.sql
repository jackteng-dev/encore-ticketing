-- M8 會員實名資料與聯絡方式驗證（模擬寄送）。只新增表格與索引，舊版程式在新 schema 上可照常運作。
-- 不蒐集身分證字號等政府證件號碼。
CREATE TABLE customer_profile (
 customer_id uuid PRIMARY KEY REFERENCES customer_account(id),
 real_name varchar(80) NULL CHECK(real_name IS NULL OR length(btrim(real_name))>0),
 email varchar(254) NULL CHECK(email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
 email_verified_at timestamptz NULL,
 phone varchar(10) NULL CHECK(phone IS NULL OR phone ~ '^09[0-9]{8}$'),
 phone_verified_at timestamptz NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(email_verified_at IS NULL OR email IS NOT NULL),
 CHECK(phone_verified_at IS NULL OR phone IS NOT NULL)
);

-- 驗證碼只存雜湊；同一會員同一管道以最新一筆為準。
CREATE TABLE contact_verification (
 id uuid PRIMARY KEY,
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 channel varchar(10) NOT NULL CHECK(channel IN ('EMAIL','PHONE')),
 target varchar(254) NOT NULL,
 code_hash char(64) NOT NULL,
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 consumed_at timestamptz NULL,
 CHECK(expires_at>created_at)
);
CREATE INDEX ON contact_verification(customer_id,channel,created_at DESC);
