-- 回调重开被拒绝的出站请求时按 order_no 查整张表，而 payment_requests 上只有
-- idempotency_key 唯一索引和 (status, next_attempt_at) 调度索引，order_no 上一个都没有。
-- 这条查询即将改成 FOR UPDATE，而 InnoDB 的锁是加在扫描到的索引记录上的：
-- 没有合适索引就会退化成扫全表、锁住无关行，把不同订单的支付请求互相阻塞。
create index idx_payment_request_order on payment_requests(order_no, id);
