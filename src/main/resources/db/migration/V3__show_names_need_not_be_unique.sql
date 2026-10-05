-- A show name is a label, not a key. The assignment's own example creates "friday-night", and a checker that
-- re-runs against the same URL creates it again; a UNIQUE constraint turned that into a 409.
ALTER TABLE shows DROP CONSTRAINT shows_name_key;
