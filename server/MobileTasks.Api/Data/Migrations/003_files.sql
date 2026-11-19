CREATE TABLE mobile_tasks.task_files (
id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES mobile_tasks.tasks(id) ON DELETE CASCADE,
author_id uuid NOT NULL REFERENCES mobile_tasks.users(id), name varchar(200) NOT NULL,
data bytea NOT NULL CHECK(octet_length(data) BETWEEN 1 AND 10485760), created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX task_files_task ON mobile_tasks.task_files(task_id);
