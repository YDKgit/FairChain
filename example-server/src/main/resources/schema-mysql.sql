-- 예제서버 DB 스키마 (ERD 그대로) — 개발 중에는 ddl-auto: update, 이 파일은 참고 · 수동 생성용
-- FK 제약은 만들지 않는다 (회원 탈퇴 시 회원 행만 삭제하고 티켓 · 주문은 남기기 때문). 관계는 주석으로만 표기.

CREATE TABLE members (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    login_id      VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    contact       VARCHAR(100) NOT NULL,
    joined_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_members_login_id (login_id)
);

CREATE TABLE concerts (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    title       VARCHAR(200) NOT NULL,
    event_open  DATETIME     NOT NULL,
    event_start DATETIME     NOT NULL,
    event_end   DATETIME     NOT NULL,
    venue       VARCHAR(200) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE seat_grades (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    concert_id BIGINT         NOT NULL,  -- → concerts.id
    grade_name VARCHAR(50)    NOT NULL,
    price      DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE seats (
    id             BIGINT                               NOT NULL AUTO_INCREMENT,
    seat_grade_id  BIGINT                               NOT NULL,  -- → seat_grades.id
    seat_number    VARCHAR(30)                          NOT NULL,
    status         ENUM ('EMPTY', 'RESERVED', 'CANCELLED') NOT NULL,
    reserved_by    BIGINT,                                         -- → members.id
    reserved_at    DATETIME,
    record_version BIGINT                               NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE tickets (
    ticket_id     BIGINT                                   NOT NULL AUTO_INCREMENT,
    member_id     BIGINT                                   NOT NULL,  -- → members.id
    seat_id       BIGINT                                   NOT NULL,  -- → seats.id
    concert_id    BIGINT                                   NOT NULL,  -- → concerts.id
    status        ENUM ('ACTIVE', 'CANCELLED', 'CHECKED_IN') NOT NULL,
    qr_token      VARCHAR(255)                             NOT NULL,
    version       BIGINT                                   NOT NULL,
    issued_at     DATETIME                                 NOT NULL,
    checked_in_at DATETIME,
    PRIMARY KEY (ticket_id),
    UNIQUE KEY uk_tickets_qr_token (qr_token)
);

CREATE TABLE orders (
    order_id     BIGINT         NOT NULL AUTO_INCREMENT,
    member_id    BIGINT         NOT NULL,  -- → members.id
    ticket_id    BIGINT         NOT NULL,  -- → tickets.ticket_id (1:1)
    paid_amount  DECIMAL(12, 2) NOT NULL,
    purchased_at TIMESTAMP      NOT NULL,
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_orders_ticket_id (ticket_id)
);

CREATE TABLE reallocation_offers (
    id              BIGINT                                          NOT NULL AUTO_INCREMENT,
    concert_id      BIGINT                                          NOT NULL,  -- → concerts.id
    seat_id         BIGINT                                          NOT NULL,  -- → seats.id
    member_id       BIGINT                                          NOT NULL,  -- → members.id
    sequence_number BIGINT                                          NOT NULL,
    offer_token     VARCHAR(255),
    status          ENUM ('WAITING', 'OFFERED', 'ACCEPTED', 'EXPIRED') NOT NULL,
    expires_at      DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reallocation_offers_offer_token (offer_token)
);

CREATE TABLE record_outbox (
    id          BIGINT                   NOT NULL AUTO_INCREMENT,
    payload     JSON                     NOT NULL,
    status      ENUM ('PENDING', 'SENT') NOT NULL,
    retry_count INT                      NOT NULL,
    created_at  DATETIME                 NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE operator_accounts (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    login_id      VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_operator_accounts_login_id (login_id)
);

CREATE TABLE gate_staff_accounts (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    login_id      VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gate_staff_accounts_login_id (login_id)
);
