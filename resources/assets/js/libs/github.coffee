checkOuterPages = (url) =>
  outerPages = ["docs", "about", "privacy", "pricing"]
  for page in outerPages
    if (url.match "^/#{page}.*")
      return "/"
  return url


CI.github =
  # we encode each parameter separately (one of them twice!) to get the right format
  authUrl: (scope=["user:email", "repo"]) =>
    destination = window.location.pathname + window.location.hash
    destination = checkOuterPages(destination)
    destination = encodeURIComponent(destination)
    csrf_token = CSRFToken
    csrf_token = encodeURIComponent(csrf_token)

    path = "https://github.com/login/oauth/authorize"
    client_id = window.renderContext.githubClientId

    l = window.location
    redirect = "#{l.protocol}//#{l.host}/auth/github?return-to=#{destination}&CSRFToken=#{csrf_token}"
    redirect = encodeURIComponent(redirect)

    url =  "#{path}?client_id=#{client_id}&redirect_uri=#{redirect}"
    if scope
      scope = scope.join ","
      scope = encodeURIComponent(scope)
      url += "&scope=#{scope}"

    url
