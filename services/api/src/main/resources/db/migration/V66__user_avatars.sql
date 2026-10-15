-- =====================================================================================
-- V66 - the photo a person chooses for their profile.
--
-- One picture per person, kept small (the interface scales it to a square before it is sent, the server refuses anything
-- over 400 KB). It belongs to the person, not to a workspace, so it follows them into every workspace they work in.
-- No tenant column and no row level security, like the users table: the only way to read it is the own-profile endpoint.
-- =====================================================================================
CREATE TABLE user_avatars (
    user_id       uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    content_type  varchar(40) NOT NULL,
    data          bytea NOT NULL,
    version       bigint NOT NULL DEFAULT 1,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_avatars_type_chk CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    CONSTRAINT user_avatars_size_chk CHECK (octet_length(data) BETWEEN 1 AND 409600)
);
