CREATE TABLE mobile_tasks.task_links (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES mobile_tasks.tasks(id) ON DELETE CASCADE,
    author_id uuid NOT NULL REFERENCES mobile_tasks.users(id),
    name varchar(160) NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 160),
    url varchar(4096) NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('web','file','image','whatsapp','telegram')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX task_links_task_created ON mobile_tasks.task_links(task_id,created_at,id);
