CI.stringHelpers =
  trimMiddle: (str, goal_len) =>
    str_len = str.length
    if str_len <= goal_len + 3
      str
    else
      over = str_len - goal_len + 3
      start_slice = Math.ceil((goal_len - 3) / 3)
      str.slice(0, start_slice) + "..." + str.slice(start_slice + over)

  linkify: (text, project_name) ->
    # urlPattern and pseudoUrlPattern are taken from http://stackoverflow.com/a/7123542

    # http://, https://, ftp://
    urlPattern = /(\b(https?|ftp):\/\/[-A-Za-z0-9+@#\/%?=~_|!:,.;]*[-A-Za-z0-9+@#\/%=~_|])/gim

    # www. sans http:// or https://
    pseudoUrlPattern = /(^|[^\/])(www\.[\S]+(\b|$))/gim

    text = text.replace(/&#x2F;/g, '/') # undo overzealous _.escape()
               .replace(urlPattern, '<a href="$1" target="_blank">$1</a>')
               .replace(pseudoUrlPattern, '$1<a href="http://$2" target="_blank">$2</a>')

    if project_name
      #issueNum
      issuePattern = /(^|\s)#(\d+)\b/g
      issueUrl = "https://github.com/#{project_name}/issues/$2"

      text = text.replace(issuePattern, "$1<a href='#{issueUrl}' target='_blank'>#$2</a>")

    text
