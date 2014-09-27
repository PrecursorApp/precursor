CI.time =
  yesterday: () =>
    moment(Date.now()).subtract("days", 1).startOf('day')

  ## takes two Dates, returns a human friendly string about how long it took
  as_time_since: (time_string) =>
    date = moment(time_string)
    now = moment(Date.now())
    yesterday = CI.time.yesterday()

    days = now.diff date, "days"
    hours = now.diff date, "hours"
    minutes = now.diff date, "minutes"
    seconds = now.diff date, "seconds"

    if minutes < 1
      "just now"
    else if hours < 1
      "#{minutes}m ago"
    else if date.clone().startOf('day').diff(yesterday) is 0 and hours > 18 and hours < 48
      "yesterday"
    else if days < 1
      date.format "h:mma"
    else if days < 365
      date.format "MMM D"
    else
      date.format "MMM YYYY"

  as_timestamp: (time_string) =>
    moment(time_string).format('llll ZZ');

  as_duration: (duration) =>
    if not duration
      return ""

    seconds = Math.floor(duration / 1000)
    minutes = Math.floor(seconds / 60)
    hours = Math.floor(minutes / 60)

    display_seconds = seconds % 60
    if display_seconds < 10
      display_seconds = "0" + display_seconds
    display_minutes = minutes % 60
    if display_minutes < 10
      display_minutes = "0" + display_minutes

    if hours > 0
      "#{hours}:#{display_minutes}:#{display_seconds}"
    else
      "#{display_minutes}:#{display_seconds}"

  as_estimated_duration: (duration) =>
    seconds = Math.floor(duration / 1000)
    minutes = Math.floor(seconds / 60)

    "#{minutes+1}m"
