CI.paths =
  org_settings: (org_name, subpage) =>
    path = "/gh/organizations/#{org_name}/settings"
    path += "##{subpage.replace('_', '-')}" if subpage
    path

  project_path: (username, project) ->
    "/gh/#{username}/#{project}"

  project_branch_path: (username, project, branch) ->
    "/gh/#{username}/#{project}/tree/#{encodeURIComponent(branch)}"

  project_settings_path: (username, project) ->
    @project_path(username, project) + "/edit"

  build_path: (username, project, build_num) ->
    "/gh/#{username}/#{project}/#{build_num}"

  org_dashboard_path: (username) =>
    "/gh/#{username}"

  org_settings_path: (username) =>
    "/gh/organizations/#{username}/settings"
