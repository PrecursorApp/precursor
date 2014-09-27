(ns frontend.models.organization)

(defn projects-by-follower
  "Returns a map of users logins to projects they follow."
  [projects]
  (reduce (fn [acc project]
            (let [logins (map :login (:followers project))]
              (reduce (fn [acc login]
                        (update-in acc [login] conj project))
                      acc logins)))
          {} projects))
