/**
 * Public entry points for programmatic use.
 *
 * The extension and CLI import the same functions; a project that wants to pipe a build into CI can import
 * this file instead of the tool layer.
 */
export {
  PhoenixClient, PhoenixError, writeModel, clientFor, scanSources, buildDataAgent, loadModel,
  editDataAgent, dashboardFor, attributeFor, statusFor, saveSnapshot, saveSchemaCopy, appendAudit,
  renderScanReport, renderBuildReport, renderModel,
} from './service.mjs';
export { buildModel, modelFromGraph, factProperties, metricSql } from './extract/model.mjs';
export { extract, extractDocument } from './extract/documents.mjs';
export { readSources } from './readers/index.mjs';
export { applyEdits, RELATIONS, NODE_TYPES } from './edits.mjs';
export { buildDashboard, renderDashboardHtml, renderDashboardMarkdown } from './dashboard.mjs';
export { attribute, runAttribution, renderAttribution } from './attribution.mjs';
export { schemaDraft, NODE_PROPERTIES, EDGE_PROPERTIES } from './graph/schema.mjs';
