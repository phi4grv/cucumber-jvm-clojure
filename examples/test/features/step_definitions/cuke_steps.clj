(use 'clojure-cukes.core)
(use 'clojure.test)

(Given #"^I have (\d+) big \"([^\"]*)\" in my belly$" [n, thing]
       (reset! belly (repeat n thing)))

(When "I eat {int} {string}" [n, thing]
      (eat (repeat n thing)))

(Then #"^I am \"([^\"]*)\"$" [mood-name]
      (assert (= (name (mood)) mood-name)))
