# Update this observable every second so that we can get updating durations
# and intervals
window.updator = ko.observable(0)

checkOverzealousUpdator = () ->
  if window.updator.getSubscriptionsCount() > 250
    window.updator = ko.observable(0) # reset all of the watchers
    _rollbar.push
      level: "error"
      msg: "over 250 subscribers to the updator, something is leaking"

setUpdate = () ->
  checkOverzealousUpdator()
  window.updator(window.updator() + 1)
  setTimeout setUpdate, 1000

setUpdate()
