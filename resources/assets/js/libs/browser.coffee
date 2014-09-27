CI.Browser or= {}

CI.Browser.scroll_to = (position) =>
  offset = if position == "top" then 0 else document.body.scrollHeight
  # Scrolling instantly is actually a smoother experience than
  # incorporating a delay. When the animation takes some time to run the
  # page jumps unpleasantly when new content is added.
  # Delays also fight the user if they try to scroll up at the same time
  # the animation is scrolling down.
  $("html, body").animate({scrollTop: offset}, 0)
