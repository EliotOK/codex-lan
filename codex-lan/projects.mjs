export function localProjects(result) {
  return (result?.projects ?? []).filter(project => project.projectKind === 'local' && (project.hostId ?? 'local') === 'local'
    && typeof project.projectId === 'string' && project.projectId.length > 0 && typeof project.label === 'string')
    .map(project => ({projectId: project.projectId, label: project.label, path: typeof project.path === 'string' ? project.path : ''}));
}
