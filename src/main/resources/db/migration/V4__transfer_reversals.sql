-- A reversal is a normal transfer in the opposite direction that points at the transfer it undoes.
-- The ledger stays append-only: history is never edited, only compensated.
ALTER TABLE transfers ADD COLUMN reverses_transfer_id UUID REFERENCES transfers (id);

-- Backstop against double refunds: at most one reversal per transfer, even if application logic is bypassed.
CREATE UNIQUE INDEX uq_transfers_reverses ON transfers (reverses_transfer_id)
    WHERE reverses_transfer_id IS NOT NULL;
