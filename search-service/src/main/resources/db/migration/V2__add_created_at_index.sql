-- Süzgeçsiz OPS listesi (yalnızca created_at sıralaması) için; taslak satırlar listelere hiç girmez
CREATE INDEX shipment_view_listing_idx ON shipment_view (created_at DESC, id DESC) WHERE merchant_id IS NOT NULL;
