-- Farms are created and renamed through the API now. The chat filters farms by name, so a name names one farm,
-- whatever its case.
CREATE UNIQUE INDEX farms_name_unique ON farms (lower(name));
