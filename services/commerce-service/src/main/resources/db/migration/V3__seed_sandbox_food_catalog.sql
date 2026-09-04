INSERT INTO merchant (
  merchant_id, merchant_no, name, status, category_code, minimum_order_cent,
  delivery_fee_cent, estimated_delivery_minutes, version, created_at, updated_at
) VALUES
  (UUID_TO_BIN('019fdf36-0001-7000-8000-000000000001'), 'SBX10001', '暖胃小馆', 'OPEN', 'CHINESE', 1500, 300, 32, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UUID_TO_BIN('019fdf36-0001-7000-8000-000000000002'), 'SBX10002', '轻盈茶咖', 'OPEN', 'DRINK', 1000, 200, 25, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO merchant_delivery_zone (merchant_id, zone_code, created_at) VALUES
  (UUID_TO_BIN('019fdf36-0001-7000-8000-000000000001'), 'CN-SH-PD', UTC_TIMESTAMP(6)),
  (UUID_TO_BIN('019fdf36-0001-7000-8000-000000000002'), 'CN-SH-PD', UTC_TIMESTAMP(6));

INSERT INTO menu_category (category_id, merchant_id, name, sort_order, status, created_at, updated_at) VALUES
  (UUID_TO_BIN('019fdf36-0002-7000-8000-000000000001'), UUID_TO_BIN('019fdf36-0001-7000-8000-000000000001'), '热销主食', 1, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UUID_TO_BIN('019fdf36-0002-7000-8000-000000000002'), UUID_TO_BIN('019fdf36-0001-7000-8000-000000000002'), '招牌饮品', 1, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO menu_item (
  item_id, merchant_id, category_id, name, description, taste_tags, allergen_tags,
  status, sort_order, created_at, updated_at
) VALUES
  (UUID_TO_BIN('019fdf36-0003-7000-8000-000000000001'), UUID_TO_BIN('019fdf36-0001-7000-8000-000000000001'), UUID_TO_BIN('019fdf36-0002-7000-8000-000000000001'), '番茄牛腩饭', '慢炖牛腩配番茄与米饭', '咸鲜,微酸', NULL, 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UUID_TO_BIN('019fdf36-0003-7000-8000-000000000002'), UUID_TO_BIN('019fdf36-0001-7000-8000-000000000002'), UUID_TO_BIN('019fdf36-0002-7000-8000-000000000002'), '茉香奶茶', '茉莉茶底与鲜奶', '清甜,奶香', '乳制品', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO menu_sku (sku_id, item_id, name, price_cent, status, version, created_at, updated_at) VALUES
  (UUID_TO_BIN('019fdf36-0004-7000-8000-000000000001'), UUID_TO_BIN('019fdf36-0003-7000-8000-000000000001'), '标准份', 2980, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UUID_TO_BIN('019fdf36-0004-7000-8000-000000000002'), UUID_TO_BIN('019fdf36-0003-7000-8000-000000000002'), '大杯', 1600, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO sku_option_group (option_group_id, item_id, name, required_flag, min_select, max_select, sort_order) VALUES
  (UUID_TO_BIN('019fdf36-0005-7000-8000-000000000001'), UUID_TO_BIN('019fdf36-0003-7000-8000-000000000002'), '甜度', TRUE, 1, 1, 1);

INSERT INTO sku_option (option_id, option_group_id, name, extra_price_cent, status, sort_order) VALUES
  (UUID_TO_BIN('019fdf36-0006-7000-8000-000000000001'), UUID_TO_BIN('019fdf36-0005-7000-8000-000000000001'), '三分糖', 0, 'ACTIVE', 1),
  (UUID_TO_BIN('019fdf36-0006-7000-8000-000000000002'), UUID_TO_BIN('019fdf36-0005-7000-8000-000000000001'), '无糖', 0, 'ACTIVE', 2);

INSERT INTO sku_inventory (sku_id, available_quantity, reserved_quantity, version, updated_at) VALUES
  (UUID_TO_BIN('019fdf36-0004-7000-8000-000000000001'), 100, 0, 0, UTC_TIMESTAMP(6)),
  (UUID_TO_BIN('019fdf36-0004-7000-8000-000000000002'), 100, 0, 0, UTC_TIMESTAMP(6));
