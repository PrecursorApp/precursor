CI.tracking =
  trackSignUp: () ->
    # track twtitter
    try
      $.getScript "//platform.twitter.com/oct.js", ()->
        twttr.conversion.trackPid('l4lg6')
    catch e
     console.error e

  trackPayer: (login) ->
    # track mixpanel
    try
      mixpanel.track('Paid')
    catch e
      console.error e
      # track perfect audience
    try
      window._pq = window._pq or []
      _pq.push ["track", "payer", {orderId: login}]
    catch e
      console.error e
    # track twtitter
    try
      $.getScript "//platform.twitter.com/oct.js", ()->
        twttr.conversion.trackPid('l4m9v')
    catch e
      console.error e
    
