-- Each signed-in user has their own dashboard. Widgets from before sign-in belong to 'local' (auth switched off).
ALTER TABLE dashboard_widgets ADD COLUMN owner text NOT NULL DEFAULT 'local';
CREATE INDEX ON dashboard_widgets (owner);

-- Who has a dashboard already: a user missing here gets the default widgets on first visit.
CREATE TABLE dashboard_owners (
  owner           text PRIMARY KEY,
  initialized_at  timestamptz NOT NULL DEFAULT now()
);
