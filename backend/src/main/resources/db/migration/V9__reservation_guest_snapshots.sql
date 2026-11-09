ALTER TABLE reservation_guest
  MODIFY COLUMN reservation_room_id BIGINT NULL,
  ADD COLUMN reservation_id BIGINT NULL AFTER reservation_room_id,
  ADD CONSTRAINT fk_reservation_guest_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  ADD INDEX idx_reservation_guest_reservation (reservation_id, is_primary);
