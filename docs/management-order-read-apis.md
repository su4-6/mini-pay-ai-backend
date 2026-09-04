# Management order read APIs

This document records the read-only order projections exposed to the operations
and administration portals. The source service remains the owner of each order;
the BFFs only authenticate, authorize, and proxy requests.

## Routes and ownership

| Projection | Operations route | Administration route | Source service |
| --- | --- | --- | --- |
| Transfer orders | `/api/v1/ops/transfers` | `/api/v1/admin/orders/transfers` | Payment Service |
| Food orders | `/api/v1/ops/food-orders` | `/api/v1/admin/orders/food-orders` | Commerce Service |
| Collection records | `/api/v1/ops/collection-records` | `/api/v1/admin/orders/collection-records` | Wallet Service |

Every list endpoint uses a zero-based `page` parameter, accepts at most 100
records per page, and returns `items`, `page`, `size`, and `total`. Portal UIs
convert their one-based table page to this contract before sending a request.

Food-order queries support order number, payment status, fulfillment status,
refund status, and time filters. Collection-record queries support bill owner,
business number, status, record type, and time filters; their list response also
contains collection, refund, and net-amount summaries. Details are addressed by
the source identifier (`orderNo` or `billId`).

## Authorization

- Operations requests require the existing `ops.portal` scope.
- Administration food and transfer requests require `admin.order.read`.
- Administration collection-record requests require `admin.wallet.read`.
- All routes are read-only. No write scope or cross-service database access was
  introduced.

## BFF configuration

The Management BFF uses `PAYMENT_SERVICE_URL`, `COMMERCE_SERVICE_URL`, and
`WALLET_SERVICE_URL`. The Admin BFF uses the corresponding
`MINIPAY_PAYMENT_INTERNAL_URL`, `MINIPAY_COMMERCE_INTERNAL_URL`, and
`MINIPAY_WALLET_INTERNAL_URL` values. Compose defaults point these variables to
the service names on the internal network.

The operations session endpoints are explicitly namespaced as
`/api/v1/ops-session` and `/api/v1/ops-csrf` for reverse-proxy routing. Existing
legacy session endpoints remain available for compatibility.
