-- Catalogue et stock de démonstration (voir docs/prd/07_modele_de_donnees.md)
INSERT INTO erp.product (sku, name, unit_price) VALUES
    ('TV-SAM-55', 'Téléviseur Samsung 55 pouces', 350000),
    ('FR-LG-01',  'Réfrigérateur LG',             425000),
    ('LAVE-01',   'Machine à laver',              280000);

INSERT INTO erp.inventory (sku, available, initial_quantity) VALUES
    ('TV-SAM-55', 10, 10),
    ('FR-LG-01',   6,  6),
    ('LAVE-01',    2,  2);
