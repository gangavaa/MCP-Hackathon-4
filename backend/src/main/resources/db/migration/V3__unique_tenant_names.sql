-- Tenants are created and renamed through the API now; a name names one tenant, whatever its case.
CREATE UNIQUE INDEX tenants_name_unique ON tenants (lower(name));
