CREATE TABLE mobile_tasks.users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE,
    name varchar(80) NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    password_hash text NOT NULL,
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE mobile_tasks.sessions (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES mobile_tasks.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);
CREATE INDEX sessions_user ON mobile_tasks.sessions(user_id);
CREATE INDEX sessions_expiry ON mobile_tasks.sessions(expires_at);

CREATE TABLE mobile_tasks.tasks (
    id uuid PRIMARY KEY,
    title varchar(160) NOT NULL CHECK (char_length(title) BETWEEN 3 AND 160),
    description text NOT NULL CHECK (char_length(description) <= 10000),
    status varchar(10) NOT NULL DEFAULT 'new' CHECK (status IN ('new','active','done')),
    author_id uuid NOT NULL REFERENCES mobile_tasks.users(id),
    assignee_id uuid REFERENCES mobile_tasks.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK ((status = 'new' AND assignee_id IS NULL AND completed_at IS NULL)
        OR (status = 'active' AND assignee_id IS NOT NULL AND completed_at IS NULL)
        OR (status = 'done' AND assignee_id IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX tasks_created ON mobile_tasks.tasks(created_at DESC, id DESC);
CREATE INDEX tasks_assignee ON mobile_tasks.tasks(assignee_id, status, created_at DESC, id DESC);

CREATE TABLE mobile_tasks.task_events (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES mobile_tasks.tasks(id),
    actor_id uuid NOT NULL REFERENCES mobile_tasks.users(id),
    kind varchar(16) NOT NULL CHECK (kind IN ('created','claimed','completed','comment')),
    comment text NOT NULL DEFAULT '' CHECK (char_length(comment) <= 4000),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX events_task ON mobile_tasks.task_events(task_id, created_at, id);
