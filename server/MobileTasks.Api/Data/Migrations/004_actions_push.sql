ALTER TABLE mobile_tasks.tasks DROP CONSTRAINT tasks_status_check;
ALTER TABLE mobile_tasks.tasks DROP CONSTRAINT tasks_check;
ALTER TABLE mobile_tasks.tasks ADD CHECK(status IN ('new','active','done','canceled'));
ALTER TABLE mobile_tasks.tasks ADD CHECK((status='new' AND assignee_id IS NULL AND completed_at IS NULL) OR (status='active' AND assignee_id IS NOT NULL AND completed_at IS NULL) OR (status='done' AND assignee_id IS NOT NULL AND completed_at IS NOT NULL) OR (status='canceled' AND completed_at IS NULL));
ALTER TABLE mobile_tasks.task_events DROP CONSTRAINT task_events_kind_check;
ALTER TABLE mobile_tasks.task_events ADD CHECK(kind IN ('created','claimed','completed','comment','released','canceled','transferred'));
CREATE TABLE mobile_tasks.devices(id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES mobile_tasks.users(id),token text NOT NULL UNIQUE,sources jsonb NOT NULL DEFAULT '[]'::jsonb,expires_at timestamptz NOT NULL DEFAULT now()+interval '30 days');
CREATE TABLE mobile_tasks.push_queue(id bigserial PRIMARY KEY,actor uuid,kind text NOT NULL,source text NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now(),attempts integer NOT NULL DEFAULT 0,next_at timestamptz NOT NULL DEFAULT now(),dedup text UNIQUE);
