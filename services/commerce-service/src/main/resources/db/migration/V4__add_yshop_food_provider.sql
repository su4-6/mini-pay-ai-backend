CREATE TABLE food_external_binding (
    binding_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(64) NOT NULL,
    provider_member_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    PRIMARY KEY (binding_id),
    UNIQUE KEY uk_food_binding_user_provider (user_id, provider),
    UNIQUE KEY uk_food_binding_subject (provider, provider_subject)
);

CREATE TABLE food_handoff_code (
    code_digest BINARY(32) NOT NULL,
    user_id BINARY(16) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    device_proof_digest BINARY(32) NOT NULL,
    allowed_origin VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (code_digest),
    KEY idx_food_handoff_expiry (expires_at)
);

CREATE TABLE food_provider_resource_ref (
    resource_ref_id BINARY(16) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (resource_ref_id),
    UNIQUE KEY uk_food_provider_resource (provider, resource_type, external_id)
);

CREATE TABLE food_ai_cart (
    cart_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    store_ref_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (cart_id),
    UNIQUE KEY uk_food_ai_cart_user_store (user_id, store_ref_id)
);

CREATE TABLE food_ai_cart_item (
    cart_item_id BINARY(16) NOT NULL,
    cart_id BINARY(16) NOT NULL,
    sku_ref_id BINARY(16) NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (cart_item_id),
    UNIQUE KEY uk_food_ai_cart_sku (cart_id, sku_ref_id),
    CONSTRAINT fk_food_ai_cart_item_cart FOREIGN KEY (cart_id) REFERENCES food_ai_cart(cart_id),
    CONSTRAINT chk_food_ai_cart_quantity CHECK (quantity BETWEEN 1 AND 99)
);

CREATE TABLE food_location_context (
    location_context_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    longitude DECIMAL(11,8) NOT NULL,
    latitude DECIMAL(10,8) NOT NULL,
    accuracy_meters DECIMAL(10,2) NULL,
    source VARCHAR(16) NOT NULL,
    captured_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (location_context_id),
    KEY idx_food_location_user_expiry (user_id, expires_at)
);

CREATE TABLE food_provider_quote_ref (
    quote_ref_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_quote_id VARCHAR(64) NOT NULL,
    store_ref_id BINARY(16) NOT NULL,
    amount_cent BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (quote_ref_id),
    UNIQUE KEY uk_food_provider_quote (provider, external_quote_id),
    KEY idx_food_quote_user (user_id, created_at)
);

CREATE TABLE food_external_order_ref (
    order_ref_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_order_no VARCHAR(64) NOT NULL,
    provider_order_ref VARCHAR(64) NULL,
    quote_ref_id BINARY(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    amount_cent BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    payment_order_id BINARY(16) NULL,
    payment_status VARCHAR(16) NOT NULL,
    fulfillment_status VARCHAR(32) NOT NULL,
    refund_status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (order_ref_id),
    UNIQUE KEY uk_food_external_order (provider, external_order_no),
    UNIQUE KEY uk_food_external_order_idempotency (user_id, idempotency_key),
    UNIQUE KEY uk_food_external_payment (payment_order_id),
    KEY idx_food_external_order_user (user_id, created_at)
);

CREATE TABLE food_provider_delivery (
    delivery_id BINARY(16) NOT NULL,
    event_id BINARY(16) NOT NULL,
    order_ref_id BINARY(16) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    last_error_code VARCHAR(96) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (delivery_id),
    UNIQUE KEY uk_food_provider_delivery_event (event_id, event_type),
    KEY idx_food_provider_delivery_pending (status, next_attempt_at)
);

CREATE TABLE food_service_nonce (
    nonce VARCHAR(96) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (nonce),
    KEY idx_food_service_nonce_expiry (expires_at)
);
