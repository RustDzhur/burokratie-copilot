-- Hardware-store receipts (paint, rollers, spatulas, tape) were barely covered: the only hit was
-- "farbe" with weight 0.5, and a false "pacht" match inside "Spachtel" won. These rules name the
-- consumables a hardware-store receipt actually contains, so the receipt lands in
-- Werkzeuge/Verbrauchsmaterial (EÜR line 51) instead of a collection node or a wrong drawer.
INSERT INTO expense_category_rules (category_id, rule_type, pattern, weight, priority, description)
SELECT c.id, 'KEYWORD', v.pattern, v.weight, 100, v.description::jsonb
FROM expense_categories c
CROSS JOIN (VALUES
    ('farbroller', 0.850, '{"de": "Farbroller", "en": "Paint roller", "ru": "Малярный валик"}'),
    ('farbwalze',  0.850, '{"de": "Farbwalze", "en": "Paint roller sleeve", "ru": "Валик"}'),
    ('pinsel',     0.850, '{"de": "Pinsel", "en": "Brush", "ru": "Кисть"}'),
    ('spachtel',   0.850, '{"de": "Spachtel", "en": "Spatula", "ru": "Шпатель"}'),
    ('kreppband',  0.850, '{"de": "Kreppband", "en": "Masking tape", "ru": "Малярный скотч"}'),
    ('schleifpapier', 0.850, '{"de": "Schleifpapier", "en": "Sandpaper", "ru": "Наждачная бумага"}'),
    ('schleifmittel', 0.800, '{"de": "Schleifmittel", "en": "Abrasives", "ru": "Абразивы"}'),
    ('abdeckfolie', 0.800, '{"de": "Abdeckfolie", "en": "Covering film", "ru": "Укрывная плёнка"}'),
    ('baumarkt',   0.550, '{"de": "Baumarkt", "en": "DIY store", "ru": "Строительный магазин"}'),
    ('poco',       0.600, '{"de": "POCO", "en": "POCO", "ru": "POCO"}'),
    ('hornbach',   0.600, '{"de": "Hornbach", "en": "Hornbach", "ru": "Hornbach"}'),
    ('bauhaus',    0.550, '{"de": "Bauhaus", "en": "Bauhaus", "ru": "Bauhaus"}'),
    ('toom',       0.550, '{"de": "toom", "en": "toom", "ru": "toom"}'),
    ('obi',        0.600, '{"de": "OBI", "en": "OBI", "ru": "OBI"}')
) AS v(pattern, weight, description)
WHERE c.code = 'werkzeuge.verbrauchsmaterial_werkzeug'
  AND NOT EXISTS (
      SELECT 1 FROM expense_category_rules r
      WHERE r.category_id = c.id AND r.pattern = v.pattern AND r.is_active
  );
