/**
 * One reference, several spellings.
 *
 * A person names a metric or a dimension by its id (`metric/营收`), by what it is called (`营收`), by an alias the
 * source gave it (`收入`, `revenue`), or by the fact property it binds to (`revenue`, `channel`). All four are the
 * same node, and two spellings of one question must produce one answer - that is what a stable surface means.
 *
 * Resolution is ordered and deterministic: an exact id wins, then an exact name, then an alias, then the binding,
 * then the column behind a dimension; inside a level the candidates are scanned in id order, so an ambiguous model
 * resolves the same way every run. `how` is returned so a report can say which spelling was understood.
 */

const PREFIX = { Metric: 'metric/', Dimension: 'dim/' };

const text = (value) => String(value ?? '').trim();
const lower = (value) => text(value).toLowerCase();

function aliasesOf(node) {
  const alias = node.properties?.alias;
  if (Array.isArray(alias)) return alias.map(text).filter(Boolean);
  if (typeof alias === 'string' && alias) return alias.split(',').map(text).filter(Boolean);
  return [];
}

/** The fact properties a binding names: `channel` or `year=year,month=month`. */
function bindingProperties(node) {
  const binding = text(node.properties?.graphProperty);
  if (!binding) return [];
  return binding.split(',')
    .map((part) => part.split('=').map(text))
    .map(([grain, property]) => (property || grain))
    .filter(Boolean);
}

/**
 * The node a reference means, and how it was matched.
 *
 * @param {object} model
 * @param {string} reference id, name, alias, binding property or column
 * @param {'Metric'|'Dimension'} entityType
 * @returns {{node:object, how:string}|null}
 */
export function resolveNode(model, reference, entityType) {
  const wanted = text(reference);
  if (!wanted) return null;
  const prefix = PREFIX[entityType];
  const wantedLower = wanted.toLowerCase();
  const candidates = (model.nodes || [])
    .filter((node) => node.entityType === entityType)
    .sort((left, right) => String(left.id).localeCompare(String(right.id)));

  // the column a dimension maps to, through the relation the model already carries
  const columnsOf = new Map();
  if (entityType === 'Dimension') {
    for (const edge of model.edges || []) {
      if (edge.relation !== 'dimensionColumn') continue;
      if (!columnsOf.has(edge.head)) columnsOf.set(edge.head, []);
      columnsOf.get(edge.head).push(String(edge.tail));
    }
  }

  const levels = [
    ['id', (node) => text(node.id) === wanted || text(node.id) === `${prefix}${wanted}`],
    ['name', (node) => lower(node.properties?.name) === wantedLower],
    ['alias', (node) => aliasesOf(node).some((alias) => alias.toLowerCase() === wantedLower)],
    ['binding', (node) => bindingProperties(node).some((property) => property.toLowerCase() === wantedLower)],
    ['expression', (node) => lower(node.properties?.expression).replace(/\s+/g, '') === wantedLower.replace(/\s+/g, '')],
    ['column', (node) => {
      const column = text(node.properties?.column);
      const bare = column.replace(/^.*\./, '');
      if (column && (column.toLowerCase() === wantedLower || bare.toLowerCase() === wantedLower)) return true;
      // `sales_day.channel` names a column node, so a table-qualified reference resolves through the edge
      return (columnsOf.get(node.id) || []).some((id) => {
        const suffix = id.replace(/^.*\//, '').toLowerCase();
        return suffix === wantedLower || suffix.endsWith(`.${wantedLower}`);
      });
    }],
  ];
  for (const [how, matches] of levels) {
    const found = candidates.find(matches);
    if (found) return { node: found, how };
  }
  return null;
}

/** The metric a reference means (id, name, alias or bound property). */
export function resolveMetric(model, reference) {
  return resolveNode(model, reference, 'Metric');
}

/** The dimension a reference means (id, name, alias, bound property or column). */
export function resolveDimension(model, reference) {
  return resolveNode(model, reference, 'Dimension');
}

/** The ids a caller can choose from, for an error a human can act on. */
export function choices(model, entityType) {
  return (model.nodes || [])
    .filter((node) => node.entityType === entityType)
    .map((node) => node.id)
    .sort();
}
