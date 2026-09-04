-- Local demo data only. Safe to re-run; deterministic keys keep it idempotent.
UPDATE minipay_payment.merchant_application
SET notify_url = 'http://callback-mock:8098/callbacks/success',
    refund_notify_url = 'http://callback-mock:8098/callbacks/success',
    configured_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
WHERE app_id IN ('app_demo_starry', 'mp_app_demo_transaction')
   OR app_id LIKE 'mp_app_019f%';

-- Keep the local system administrator on the same LOGIN_PASSWORD flow as the
-- password-change API. Reuse the known demo merchant hash without storing a
-- plaintext or a second environment-specific hash in this seed file.
UPDATE minipay_identity.user_credential admin_credential
JOIN minipay_identity.user_role admin_role
  ON admin_role.user_id = admin_credential.user_id
JOIN (
  SELECT credential.password_hash
  FROM minipay_identity.user_credential credential
  JOIN minipay_identity.user_role role ON role.user_id = credential.user_id
  WHERE role.role_code = 'merchant_admin'
    AND credential.credential_type = 'MERCHANT_LOGIN_PASSWORD'
    AND credential.status = 'ACTIVE'
  LIMIT 1
) demo_password
SET admin_credential.password_hash = demo_password.password_hash,
    admin_credential.status = 'ACTIVE',
    admin_credential.failed_attempts = 0,
    admin_credential.locked_until = NULL,
    admin_credential.updated_at = UTC_TIMESTAMP(6)
WHERE admin_role.role_code = 'system_super_admin'
  AND admin_credential.credential_type = 'LOGIN_PASSWORD';

INSERT INTO minipay_payment.bank_card
(card_id,user_id,provider,provider_token,bank_name,card_type,masked_card_no,last_four,holder_name,status,verified_at,created_at,updated_at)
VALUES
(UNHEX('019FB3D0200070008000000000000001'),UNHEX('019FB3D0000070008000000000000121'),'SANDBOX','demo-card-owner','演示银行','DEBIT','6222 **** **** 0001','0001','演示店主','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(UNHEX('019FB3D0200070008000000000000002'),UNHEX('019FB3D0000070008000000000000131'),'SANDBOX','demo-card-payer','演示银行','DEBIT','6222 **** **** 0002','0002','演示付款人','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE status=VALUES(status),updated_at=UTC_TIMESTAMP(6);

INSERT INTO minipay_payment.transfer_order
(transfer_id,transfer_no,client_request_id,intent_id,payer_account_id,receiver_account_id,amount_cent,status,failure_code,version,created_at,updated_at)
VALUES
(UNHEX('019FB3D0210070008000000000000001'),'T202608110000000001','demo-transfer-1',UNHEX('019FB3D0211070008000000000000001'),UNHEX('019FEB66801D789A9A1586C67BCA5973'),UNHEX('019FEB666558741AA733338E8584627E'),1880,'SUCCEEDED',NULL,0,UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)-INTERVAL 1 DAY),
(UNHEX('019FB3D0210070008000000000000002'),'T202608110000000002','demo-transfer-2',UNHEX('019FB3D0211070008000000000000002'),UNHEX('019FEB666558741AA733338E8584627E'),UNHEX('019FEB66801D789A9A1586C67BCA5973'),5200,'SUCCEEDED',NULL,0,UTC_TIMESTAMP(6)-INTERVAL 2 DAY,UTC_TIMESTAMP(6)-INTERVAL 2 DAY),
(UNHEX('019FB3D0210070008000000000000003'),'T202608110000000003','demo-transfer-3',UNHEX('019FB3D0211070008000000000000003'),UNHEX('019FEB66801D789A9A1586C67BCA5973'),UNHEX('019FEB666558741AA733338E8584627E'),9900,'FAILED','INSUFFICIENT_BALANCE',0,UTC_TIMESTAMP(6)-INTERVAL 3 DAY,UTC_TIMESTAMP(6)-INTERVAL 3 DAY),
(UNHEX('019FB3D0210070008000000000000004'),'T202608110000000004','demo-transfer-4',UNHEX('019FB3D0211070008000000000000004'),UNHEX('019FEB666558741AA733338E8584627E'),UNHEX('019FEB66801D789A9A1586C67BCA5973'),6600,'PROCESSING',NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE status=VALUES(status),failure_code=VALUES(failure_code),updated_at=VALUES(updated_at);

INSERT INTO minipay_payment.recharge_order
(recharge_id,recharge_no,user_id,idempotency_key,request_hash,amount_cent,bank_card_id,channel,status,failure_code,version,created_at,updated_at)
VALUES
(UNHEX('019FB3D0220070008000000000000001'),'C202608110000000001',UNHEX('019FB3D0000070008000000000000121'),'demo-recharge-1',UNHEX(SHA2('demo-recharge-1',256)),10000,UNHEX('019FB3D0200070008000000000000001'),'BANK_CARD','SUCCEEDED',NULL,0,UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)-INTERVAL 1 DAY),
(UNHEX('019FB3D0220070008000000000000002'),'C202608110000000002',UNHEX('019FB3D0000070008000000000000131'),'demo-recharge-2',UNHEX(SHA2('demo-recharge-2',256)),20000,UNHEX('019FB3D0200070008000000000000002'),'BANK_CARD','SUCCEEDED',NULL,0,UTC_TIMESTAMP(6)-INTERVAL 2 DAY,UTC_TIMESTAMP(6)-INTERVAL 2 DAY),
(UNHEX('019FB3D0220070008000000000000003'),'C202608110000000003',UNHEX('019FB3D0000070008000000000000131'),'demo-recharge-3',UNHEX(SHA2('demo-recharge-3',256)),5000,UNHEX('019FB3D0200070008000000000000002'),'BANK_CARD','FAILED','BANK_REJECTED',0,UTC_TIMESTAMP(6)-INTERVAL 3 DAY,UTC_TIMESTAMP(6)-INTERVAL 3 DAY),
(UNHEX('019FB3D0220070008000000000000004'),'C202608110000000004',UNHEX('019FB3D0000070008000000000000121'),'demo-recharge-4',UNHEX(SHA2('demo-recharge-4',256)),30000,UNHEX('019FB3D0200070008000000000000001'),'BANK_CARD','PROCESSING',NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE status=VALUES(status),failure_code=VALUES(failure_code),updated_at=VALUES(updated_at);

INSERT INTO minipay_payment.withdrawal_order
(withdrawal_id,withdrawal_no,user_id,bank_card_id,idempotency_key,request_hash,amount_cent,status,bank_request_no,failure_code,version,created_at,updated_at)
VALUES
(UNHEX('019FB3D0230070008000000000000001'),'W202608110000000001',UNHEX('019FB3D0000070008000000000000121'),UNHEX('019FB3D0200070008000000000000001'),'demo-withdrawal-1',UNHEX(SHA2('demo-withdrawal-1',256)),5000,'SUCCEEDED','DEMO-BANK-001',NULL,0,UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)-INTERVAL 1 DAY),
(UNHEX('019FB3D0230070008000000000000002'),'W202608110000000002',UNHEX('019FB3D0000070008000000000000131'),UNHEX('019FB3D0200070008000000000000002'),'demo-withdrawal-2',UNHEX(SHA2('demo-withdrawal-2',256)),8800,'PROCESSING','DEMO-BANK-002',NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(UNHEX('019FB3D0230070008000000000000003'),'W202608110000000003',UNHEX('019FB3D0000070008000000000000131'),UNHEX('019FB3D0200070008000000000000002'),'demo-withdrawal-3',UNHEX(SHA2('demo-withdrawal-3',256)),12000,'FAILED',NULL,'BANK_REJECTED',0,UTC_TIMESTAMP(6)-INTERVAL 2 DAY,UTC_TIMESTAMP(6)-INTERVAL 2 DAY)
ON DUPLICATE KEY UPDATE status=VALUES(status),failure_code=VALUES(failure_code),updated_at=VALUES(updated_at);

INSERT INTO minipay_payment.refund_order
(refund_order_id,refund_order_no,pay_order_id,merchant_refund_no,amount_cent,reason,status,failure_code,version,created_at,updated_at)
VALUES
(UNHEX('019FB3D0290070008000000000000001'),'R202608110000000011',UNHEX('019FB3D0120070008000000000000011'),'demo-refund-11',590,'用户申请退款','SUCCEEDED',NULL,0,UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)-INTERVAL 1 DAY),
(UNHEX('019FB3D0290070008000000000000002'),'R202608110000000012',UNHEX('019FB3D0120070008000000000000012'),'demo-refund-12',1200,'商品缺货','PROCESSING',NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(UNHEX('019FB3D0290070008000000000000003'),'R202608110000000013',UNHEX('019FB3D0120070008000000000000013'),'demo-refund-13',1280,'支付渠道退款失败','FAILED','CHANNEL_REJECTED',0,UTC_TIMESTAMP(6)-INTERVAL 2 DAY,UTC_TIMESTAMP(6)-INTERVAL 2 DAY)
ON DUPLICATE KEY UPDATE status=VALUES(status),failure_code=VALUES(failure_code),updated_at=VALUES(updated_at);

INSERT INTO minipay_wallet.wallet_bill
(bill_id,owner_id,account_id,business_type,business_no,source,direction,amount_cent,counterparty_display,remark,status,balance_after_cent,occurred_at,created_at,updated_at)
VALUES
(UNHEX('019FB3D0240070008000000000000001'),UNHEX('019FB3D0000070008000000000000121'),UNHEX('019FEB666558741AA733338E8584627E'),'TRANSFER','T202608110000000001','DEMO','INCOME',1880,'演示付款人','转账收款','SUCCEEDED',994120,UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(UNHEX('019FB3D0240070008000000000000002'),UNHEX('019FB3D0000070008000000000000121'),UNHEX('019FEB666558741AA733338E8584627E'),'PAYMENT','P202608100000000011','DEMO','INCOME',2590,'星河便利店','经营收款','SUCCEEDED',996710,UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(UNHEX('019FB3D0240070008000000000000003'),UNHEX('019FB3D0000070008000000000000121'),UNHEX('019FEB666558741AA733338E8584627E'),'REFUND','R202608110000000001','DEMO','EXPENSE',680,'演示付款人','订单退款','SUCCEEDED',996030,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(UNHEX('019FB3D0240070008000000000000004'),UNHEX('019FB3D0000070008000000000000131'),UNHEX('019FEB66801D789A9A1586C67BCA5973'),'RECHARGE','C202608110000000002','DEMO','INCOME',20000,'演示银行','余额充值','SUCCEEDED',1026880,UTC_TIMESTAMP(6)-INTERVAL 2 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(UNHEX('019FB3D0240070008000000000000005'),UNHEX('019FB3D0000070008000000000000131'),UNHEX('019FEB66801D789A9A1586C67BCA5973'),'WITHDRAWAL','W202608110000000003','DEMO','EXPENSE',12000,'演示银行','提现失败','FAILED',1026880,UTC_TIMESTAMP(6)-INTERVAL 2 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE status=VALUES(status),balance_after_cent=VALUES(balance_after_cent),updated_at=UTC_TIMESTAMP(6);

INSERT INTO minipay_commerce.delivery_address
(address_id,user_id,label,masked_summary,recipient_ciphertext,mobile_ciphertext,address_ciphertext,encryption_key_version,zone_code,is_default,status,version,created_at,updated_at)
VALUES (UNHEX('019FB3D0250070008000000000000001'),UNHEX('019FB3D0000070008000000000000131'),'公司','北京市朝阳区演示地址',X'01',X'02',X'03',1,'DEMO-ZONE',1,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE masked_summary=VALUES(masked_summary),updated_at=UTC_TIMESTAMP(6);

INSERT INTO minipay_commerce.shopping_cart
(cart_id,user_id,merchant_id,version,created_at,updated_at)
SELECT UNHEX('019FB3D0260070008000000000000001'),UNHEX('019FB3D0000070008000000000000131'),merchant_id,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM minipay_commerce.merchant ORDER BY merchant_no LIMIT 1
ON DUPLICATE KEY UPDATE updated_at=UTC_TIMESTAMP(6);

INSERT INTO minipay_commerce.checkout_quote
(quote_id,user_id,cart_id,merchant_id,address_id,cart_version,item_amount_cent,delivery_fee_cent,discount_cent,payable_amount_cent,currency,status,request_hash,expires_at,version,created_at)
SELECT UNHEX('019FB3D0270070008000000000000001'),UNHEX('019FB3D0000070008000000000000131'),UNHEX('019FB3D0260070008000000000000001'),merchant_id,UNHEX('019FB3D0250070008000000000000001'),0,5800,500,300,6000,'CNY','CONSUMED',UNHEX(SHA2('demo-food-quote-1',256)),UTC_TIMESTAMP(6)+INTERVAL 1 DAY,0,UTC_TIMESTAMP(6)
FROM minipay_commerce.merchant ORDER BY merchant_no LIMIT 1
ON DUPLICATE KEY UPDATE status=VALUES(status);

INSERT INTO minipay_commerce.food_order
(order_id,order_no,user_id,merchant_id,merchant_name_snapshot,address_id,address_summary_snapshot,quote_id,payment_order_id,item_amount_cent,delivery_fee_cent,discount_cent,payable_amount_cent,currency,status,payment_status,refund_status,request_hash,expires_at,version,created_at,updated_at)
SELECT UNHEX('019FB3D0280070008000000000000001'),'F202608110000000001',UNHEX('019FB3D0000070008000000000000131'),merchant_id,name,UNHEX('019FB3D0250070008000000000000001'),'北京市朝阳区演示地址',UNHEX('019FB3D0270070008000000000000001'),NULL,5800,500,300,6000,'CNY','DELIVERED','SUCCEEDED','NONE',UNHEX(SHA2('demo-food-order-1',256)),UTC_TIMESTAMP(6)+INTERVAL 1 DAY,0,UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)
FROM minipay_commerce.merchant ORDER BY merchant_no LIMIT 1
ON DUPLICATE KEY UPDATE status=VALUES(status),payment_status=VALUES(payment_status),updated_at=UTC_TIMESTAMP(6);

INSERT INTO minipay_commerce.checkout_quote
(quote_id,user_id,cart_id,merchant_id,address_id,cart_version,item_amount_cent,delivery_fee_cent,discount_cent,payable_amount_cent,currency,status,request_hash,expires_at,version,created_at)
SELECT UNHEX(quote_hex),UNHEX('019FB3D0000070008000000000000131'),UNHEX('019FB3D0260070008000000000000001'),m.merchant_id,UNHEX('019FB3D0250070008000000000000001'),0,item_amount,500,discount_amount,item_amount+500-discount_amount,'CNY','CONSUMED',UNHEX(SHA2(request_text,256)),UTC_TIMESTAMP(6)+INTERVAL 1 DAY,0,UTC_TIMESTAMP(6)
FROM minipay_commerce.merchant m
JOIN (
  SELECT '019FB3D0270070008000000000000002' quote_hex,3200 item_amount,0 discount_amount,'demo-food-quote-2' request_text
  UNION ALL SELECT '019FB3D0270070008000000000000003',7600,600,'demo-food-quote-3'
  UNION ALL SELECT '019FB3D0270070008000000000000004',4500,500,'demo-food-quote-4'
) q
WHERE m.merchant_no=(SELECT MIN(merchant_no) FROM minipay_commerce.merchant)
ON DUPLICATE KEY UPDATE status=VALUES(status);

INSERT INTO minipay_commerce.food_order
(order_id,order_no,user_id,merchant_id,merchant_name_snapshot,address_id,address_summary_snapshot,quote_id,payment_order_id,item_amount_cent,delivery_fee_cent,discount_cent,payable_amount_cent,currency,status,payment_status,refund_status,request_hash,expires_at,version,created_at,updated_at)
SELECT UNHEX(d.order_hex),d.order_no,UNHEX('019FB3D0000070008000000000000131'),m.merchant_id,m.name,UNHEX('019FB3D0250070008000000000000001'),'北京市朝阳区演示地址',UNHEX(d.quote_hex),NULL,d.item_amount,500,d.discount_amount,d.item_amount+500-d.discount_amount,'CNY',d.order_status,d.payment_status,d.refund_status,UNHEX(SHA2(d.request_text,256)),UTC_TIMESTAMP(6)+INTERVAL 1 DAY,0,UTC_TIMESTAMP(6)-INTERVAL d.age_day DAY,UTC_TIMESTAMP(6)
FROM minipay_commerce.merchant m
JOIN (
  SELECT '019FB3D0280070008000000000000002' order_hex,'F202608110000000002' order_no,'019FB3D0270070008000000000000002' quote_hex,3200 item_amount,0 discount_amount,'PENDING_PAYMENT' order_status,'UNPAID' payment_status,'NONE' refund_status,'demo-food-order-2' request_text,0 age_day
  UNION ALL SELECT '019FB3D0280070008000000000000003','F202608110000000003','019FB3D0270070008000000000000003',7600,600,'PREPARING','SUCCEEDED','NONE','demo-food-order-3',1
  UNION ALL SELECT '019FB3D0280070008000000000000004','F202608110000000004','019FB3D0270070008000000000000004',4500,500,'CANCELLED','SUCCEEDED','SUCCEEDED','demo-food-order-4',2
) d
WHERE m.merchant_no=(SELECT MIN(merchant_no) FROM minipay_commerce.merchant)
ON DUPLICATE KEY UPDATE status=VALUES(status),payment_status=VALUES(payment_status),refund_status=VALUES(refund_status),updated_at=UTC_TIMESTAMP(6);
