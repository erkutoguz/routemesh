-- Taslak satır sayısı (routemesh.search.view.skeleton.rows) her metrik okumasında sayılır; küçük kısmi indeks bunu ucuz tutar
CREATE INDEX shipment_view_skeleton_idx ON shipment_view (id) WHERE merchant_id IS NULL;
