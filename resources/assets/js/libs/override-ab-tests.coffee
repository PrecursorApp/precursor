parseQuery = (search) ->
  params = {}
  for query in search.substr(1).split("&")
    pair = query.split("=")
    if pair.length is 2
      params[pair[0]] = decodeURIComponent(pair[1])
  params

CI.maybeOverrideABTests = (search, ab_tests) ->
  params = parseQuery(search)

  for own k, v of params
    if ab_tests()[k]
      switch v
        when 'true' then ab_tests()[k](true)
        when 'false' then ab_tests()[k](false)
        else ab_tests()[k](v)
