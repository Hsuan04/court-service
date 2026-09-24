CREATE TABLE court (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    address     VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE member (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE court_session (
    id            BIGSERIAL PRIMARY KEY,
    court_id      BIGINT NOT NULL REFERENCES court(id),
    session_date  DATE NOT NULL,
    start_time    TIME NOT NULL,
    end_time      TIME NOT NULL,
    capacity      INT NOT NULL,
    booked_count  INT NOT NULL DEFAULT 0,
    open_at       TIMESTAMPTZ NOT NULL,
    close_at      TIMESTAMPTZ NOT NULL,
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_session_time      CHECK (end_time > start_time),
    CONSTRAINT chk_session_open      CHECK (close_at > open_at),
    CONSTRAINT chk_session_capacity  CHECK (capacity > 0),
    CONSTRAINT chk_session_booked    CHECK (booked_count >= 0 AND booked_count <= capacity)
);

CREATE TABLE booking (
    id                BIGSERIAL PRIMARY KEY,
    court_session_id  BIGINT NOT NULL REFERENCES court_session(id),
    member_id         BIGINT NOT NULL REFERENCES member(id),
    status            VARCHAR(20) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_booking_session_member UNIQUE (court_session_id, member_id),
    CONSTRAINT chk_booking_status CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);

CREATE INDEX idx_court_session_court_id ON court_session(court_id);
CREATE INDEX idx_booking_member_id ON booking(member_id);