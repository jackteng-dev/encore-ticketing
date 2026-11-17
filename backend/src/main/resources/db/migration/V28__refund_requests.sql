-- M7 退票申請與審核（模擬退款）。只新增表格與索引；不改訂單、票券、票額。
-- 核准只記錄模擬退款；票券撤銷仍走既有受保護的撤銷流程，票額不自動回補。
CREATE TABLE refund_request (
 id uuid PRIMARY KEY,
 order_id uuid NOT NULL,
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 request_id uuid NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),
 amount_twd integer NOT NULL CHECK(amount_twd>0),
 status varchar(20) NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
 review_note varchar(500) NULL,
 reviewed_by uuid NULL REFERENCES staff_account(id),
 reviewed_at timestamptz NULL,
 mock_refund_id uuid NULL,
 withdrawn_at timestamptz NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(customer_id,request_id),
 FOREIGN KEY(order_id,customer_id) REFERENCES ticket_order(id,customer_id),
 CHECK((status='PENDING' AND reviewed_at IS NULL AND mock_refund_id IS NULL AND withdrawn_at IS NULL) OR
       (status='WITHDRAWN' AND reviewed_at IS NULL AND mock_refund_id IS NULL AND withdrawn_at IS NOT NULL) OR
       (status='APPROVED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL AND mock_refund_id IS NOT NULL) OR
       (status='REJECTED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL AND review_note IS NOT NULL AND mock_refund_id IS NULL))
);
-- 同一訂單同時只能有一筆處理中或已核准的申請。
CREATE UNIQUE INDEX refund_one_open ON refund_request(order_id) WHERE status IN ('PENDING','APPROVED');
CREATE INDEX ON refund_request(status,created_at,id);
CREATE INDEX ON refund_request(customer_id,created_at DESC,id);

-- 資料庫層防護：已核准退票的訂單不可再出票或驗票入場。
-- 放在資料庫而非只放程式，確保 CD 回退到舊版（不認得退票）時規則仍成立。
-- 只在存在 APPROVED 退票時才拒絕；沒有退票資料時對既有流程無任何影響。
CREATE FUNCTION refund_blocks_issuance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM refund_request WHERE order_id=NEW.order_id AND status='APPROVED') THEN
  RAISE EXCEPTION 'order % has an approved refund', NEW.order_id USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER refund_blocks_issuance BEFORE INSERT ON ticket_issuance FOR EACH ROW EXECUTE FUNCTION refund_blocks_issuance();

CREATE FUNCTION refund_blocks_check_in() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM refund_request WHERE order_id=NEW.order_id AND status='APPROVED') THEN
  RAISE EXCEPTION 'ticket % belongs to an order with an approved refund', NEW.id USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER refund_blocks_check_in BEFORE UPDATE OF state ON issued_ticket FOR EACH ROW
 WHEN (NEW.state='CHECKED_IN' AND OLD.state IS DISTINCT FROM 'CHECKED_IN') EXECUTE FUNCTION refund_blocks_check_in();
