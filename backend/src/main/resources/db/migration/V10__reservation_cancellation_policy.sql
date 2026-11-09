ALTER TABLE reservation
  ADD COLUMN cancellation_policy VARCHAR(32) NOT NULL DEFAULT 'STANDARD_24H' AFTER total_amount;
