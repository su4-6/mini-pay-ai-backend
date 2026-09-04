-- Local Docker cleanup only.
-- Keeps the standard service schemas: minipay_identity, minipay_payment, minipay_wallet, minipay_agent and seata.
-- Removes only obsolete isolated IDEA and smoke-test schemas. Run only in the local docker mysql-core container.

DROP DATABASE IF EXISTS minipay_identity_idea_dev;
DROP DATABASE IF EXISTS minipay_payment_idea_dev;
DROP DATABASE IF EXISTS minipay_wallet_idea_dev;
DROP DATABASE IF EXISTS minipay_payment_smoke_20260805;
