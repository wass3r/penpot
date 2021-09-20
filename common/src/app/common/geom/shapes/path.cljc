;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.path
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gsc]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]
   [app.common.path.commands :as upc]))

(def ^:const curve-curve-precision 0.1)

(defn calculate-opposite-handler
  "Given a point and its handler, gives the symetric handler"
  [point handler]
  (let [handler-vector (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate handler-vector))))

(defn opposite-handler
  "Calculates the coordinates of the opposite handler"
  [point handler]
  (let [phv (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate phv))))

(defn opposite-handler-keep-distance
  "Calculates the coordinates of the opposite handler but keeping the old distance"
  [point handler old-opposite]
  (let [old-distance (gpt/distance point old-opposite)
        phv (gpt/to-vec point handler)
        phv2 (gpt/multiply
              (gpt/unit (gpt/negate phv))
              (gpt/point old-distance))]
    (gpt/add point phv2)))

(defn content->points
  "Returns the points in the given content"
  [content]
  (->> content
       (map #(when (-> % :params :x)
               (gpt/point (-> % :params :x) (-> % :params :y))))
       (remove nil?)
       (into [])))

(defn line-values
  [[from-p to-p] t]
  (let [move-v (-> (gpt/to-vec from-p to-p)
                   (gpt/scale t))]
    (gpt/add from-p move-v)))

(defn line-windup
  [[_ to-p :as l] t]
  (let [p (line-values l t)
        v (gpt/to-vec p to-p)]
    (cond
      (> (:y v) 0)  1
      (< (:y v) 0) -1
      :else         0)))

;; https://medium.com/@Acegikmo/the-ever-so-lovely-b%C3%A9zier-curve-eb27514da3bf
;; https://en.wikipedia.org/wiki/Bernstein_polynomial
(defn curve-values
  "Parametric equation for cubic beziers. Given a start and end and
  two intermediate points returns points for values of t.
  If you draw t on a plane you got the bezier cube"
  ([[start end h1 h2] t]
   (curve-values start end h1 h2 t))

  ([start end h1 h2 t]
   (let [t2 (* t t) ;; t square
         t3 (* t2 t) ;; t cube

         start-v (+ (- t3) (* 3 t2) (* -3 t) 1)
         h1-v    (+ (* 3 t3) (* -6 t2) (* 3 t))
         h2-v    (+ (* -3 t3) (* 3 t2))
         end-v   t3

         coord-v (fn [coord]
                   (+ (* (coord start) start-v)
                      (* (coord h1)    h1-v)
                      (* (coord h2)    h2-v)
                      (* (coord end)   end-v)))]

     (gpt/point (coord-v :x) (coord-v :y)))))

(defn curve-tangent
  "Retrieve the tangent vector to the curve in the point `t`"
  [[start end h1 h2] t]
  
  (let [coords [[(:x start) (:x h1) (:x h2) (:x end)]
                [(:y start) (:y h1) (:y h2) (:y end)]]

        solve-derivative
        (fn [[c0 c1 c2 c3]]
          ;; Solve B'(t) given t to retrieve the value for the
          ;; first derivative
          (let [t2 (* t t)]
            (+ (* c0 (+ (* -3 t2) (*   6 t) -3))
               (* c1 (+ (*  9 t2) (* -12 t)  3))
               (* c2 (+ (* -9 t2) (*   6 t)))
               (* c3 (* 3 t2)))))

        [x y] (->> coords (mapv solve-derivative))

        ;; normalize value
        d (mth/sqrt (+ (* x x) (* y y)))]

    (gpt/point (/ x d) (/ y d))))

(defn curve-windup
  [curve t]

  (let [tangent (curve-tangent curve t)]
    (cond
      (> (:y tangent) 0)  1
      (< (:y tangent) 0) -1
      :else               0)))

(defn curve-split
  "Splits a curve into two at the given parametric value `t`.
  Calculates the Casteljau's algorithm intermediate points"
  ([[start end h1 h2] t]
   (curve-split start end h1 h2 t))

  ([start end h1 h2 t]
   (let [p1 (gpt/lerp start h1 t)
         p2 (gpt/lerp h1 h2 t)
         p3 (gpt/lerp h2 end t)
         p4 (gpt/lerp p1 p2 t)
         p5 (gpt/lerp p2 p3 t)
         sp (gpt/lerp p4 p5 t)]
     [[start sp  p1 p4]
      [sp    end p5 p3]])))

(defn subcurve-range
  "Given a curve returns a new curve between the values t1-t2"
  ([[start end h1 h2] [t1 t2]]
   (subcurve-range start end h1 h2 t1 t2))

  ([[start end h1 h2] t1 t2]
   (subcurve-range start end h1 h2 t1 t2))

  ([start end h1 h2 t1 t2]
   ;; Make sure that t2 is greater than t1
   (let [[t1 t2] (if (< t1 t2) [t1 t2] [t2 t1])
         t2' (/ (- t2 t1) (- 1 t1))
         [_ curve'] (curve-split start end h1 h2 t1)]
     (first (curve-split curve' t2')))))


;; https://trans4mind.com/personal_development/mathematics/polynomials/cubicAlgebra.htm
(defn- solve-roots
  "Solvers a quadratic or cubic equation given by the parameters a b c d"
  ([a b c]
   (solve-roots a b c 0))

  ([a b c d]
   (let [sqrt-b2-4ac (mth/sqrt (- (* b b) (* 4 a c)))]
     (cond
       ;; No solutions
       (and (mth/almost-zero? d) (mth/almost-zero? a) (mth/almost-zero? b))
       []

       ;; Linear solution
       (and (mth/almost-zero? d) (mth/almost-zero? a))
       [(/ (- c) b)]

       ;; Cuadratic
       (mth/almost-zero? d)
       [(/ (+ (- b) sqrt-b2-4ac)
           (* 2 a))
        (/ (- (- b) sqrt-b2-4ac)
           (* 2 a))]

       ;; Cubic
       :else
       (let [a (/ a d)
             b (/ b d)
             c (/ c d)

             p  (/ (- (* 3 b) (* a a)) 3)
             q (/ (+ (* 2 a a a) (* -9 a b) (* 27 c)) 27)

             p3 (/ p 3)
             q2 (/ q 2)
             discriminant (+ (* q2 q2) (* p3 p3 p3))]

         (cond
           (< discriminant 0)
           (let [mp3 (/ (- p) 3)
                 mp33 (* mp3 mp3 mp3)
                 r (mth/sqrt mp33)
                 t (/ (- q) (* 2 r))
                 cosphi (cond (< t -1) -1
                              (> t 1) 1
                              :else t)
                 phi (mth/acos cosphi)
                 crtr (mth/cubicroot r)
                 t1 (* 2 crtr)
                 root1 (- (* t1 (mth/cos (/ phi 3))) (/ a 3))
                 root2 (- (* t1 (mth/cos (/ (+ phi (* 2 mth/PI)) 3))) (/ a 3))
                 root3 (- (* t1 (mth/cos (/ (+ phi (* 4 mth/PI)) 3))) (/ a 3))]

             [root1 root2 root3])

           (= discriminant 0)
           (let [u1 (if (< q2 0) (mth/cubicroot (- q2)) (- (mth/cubicroot q2)))
                 root1 (- (* 2 u1) (/ a 3))
                 root2 (- (- u1) (/ a 3))]
             [root1 root2])

           :else
           (let [sd (mth/sqrt discriminant)
                 u1 (mth/cubicroot (- sd q2))
                 v1 (mth/cubicroot (+ sd q2))
                 root (- u1 v1 (/ a 3))]
             [root])))))))

;; https://pomax.github.io/bezierinfo/#extremities
(defn curve-extremities
  "Calculates the extremities by solving the first derivative for a cubic
  bezier and then solving the quadratic formula"
  ([[start end h1 h2]]
   (curve-extremities start end h1 h2))

  ([start end h1 h2]

   (let [coords [[(:x start) (:x h1) (:x h2) (:x end)]
                 [(:y start) (:y h1) (:y h2) (:y end)]]

         coord->tvalue
         (fn [[c0 c1 c2 c3]]
           (let [a (+ (* -3 c0) (*   9 c1) (* -9 c2) (* 3 c3))
                 b (+ (*  6 c0) (* -12 c1) (* 6 c2))
                 c (+ (*  3 c1) (*  -3 c0))]

             (solve-roots a b c)))]
     (->> coords
          (mapcat coord->tvalue)

          ;; Only values in the range [0, 1] are valid
          (filterv #(and (> % 0.01) (< % 0.99)))))))

(defn curve-roots
  "Uses cardano algorithm to find the roots for a cubic bezier"
  ([[start end h1 h2] coord]
   (curve-roots start end h1 h2 coord))

  ([start end h1 h2 coord]

   (let [coords [[(get start coord) (get h1 coord) (get h2 coord) (get end coord)]]

         coord->tvalue
         (fn [[pa pb pc pd]]

           (let [a (+ (* 3 pa) (* -6 pb) (* 3 pc))
                 b (+ (* -3 pa) (* 3 pb))
                 c pa
                 d (+ (- pa) (* 3 pb) (* -3 pc) pd)]

             (solve-roots a b c d)))]
     (->> coords
          (mapcat coord->tvalue)

          ;; Only values in the range [0, 1] are valid
          #_(filterv #(and (> % 0.01) (< % 0.99)))
          (filterv #(and (>= % 0) (<= % 1)))))))

(defn command->point
  ([command] (command->point command nil))
  ([{params :params} coord]
   (let [prefix (if coord (name coord) "")
         xkey (keyword (str prefix "x"))
         ykey (keyword (str prefix "y"))
         x (get params xkey)
         y (get params ykey)]
     (gpt/point x y))))

(defn command->line
  ([cmd]
   (command->line cmd (:prev cmd)))
  ([cmd prev]
   [prev (command->point cmd)]))

(defn command->bezier
  ([cmd]
   (command->bezier cmd (:prev cmd)))
  ([cmd prev]
   [prev
    (command->point cmd)
    (gpt/point (-> cmd :params :c1x) (-> cmd :params :c1y))
    (gpt/point (-> cmd :params :c2x) (-> cmd :params :c2y))]))

(defn content->selrect [content]
  (let [calc-extremities
        (fn [command prev]
          (case (:command command)
            :move-to [(command->point command)]

            ;; If it's a line we add the beginning point and endpoint
            :line-to [(command->point prev)
                      (command->point command)]

            ;; We return the bezier extremities
            :curve-to (d/concat
                       [(command->point prev)
                        (command->point command)]
                       (let [curve [(command->point prev)
                                    (command->point command)
                                    (command->point command :c1)
                                    (command->point command :c2)]]
                         (->> (curve-extremities curve)
                              (mapv #(curve-values curve %)))))
            []))

        extremities (mapcat calc-extremities
                            content
                            (d/concat [nil] content))

        selrect (gpr/points->selrect extremities)]

    (-> selrect
        (update :width #(if (mth/almost-zero? %) 1 %))
        (update :height #(if (mth/almost-zero? %) 1 %)))))

(defn move-content [content move-vec]
  (let [set-tr (fn [params px py]
                 (let [tr-point (-> (gpt/point (get params px) (get params py))
                                    (gpt/add move-vec))]
                   (assoc params
                          px (:x tr-point)
                          py (:y tr-point))))

        transform-params
        (fn [{:keys [x c1x c2x] :as params}]
          (cond-> params
            (not (nil? x))   (set-tr :x :y)
            (not (nil? c1x)) (set-tr :c1x :c1y)
            (not (nil? c2x)) (set-tr :c2x :c2y)))]

    (mapv #(update % :params transform-params) content)))

(defn transform-content
  [content transform]
  (let [set-tr (fn [params px py]
                 (let [tr-point (-> (gpt/point (get params px) (get params py))
                                    (gpt/transform transform))]
                   (assoc params
                          px (:x tr-point)
                          py (:y tr-point))))

        transform-params
        (fn [{:keys [x c1x c2x] :as params}]
          (cond-> params
            (not (nil? x))   (set-tr :x :y)
            (not (nil? c1x)) (set-tr :c1x :c1y)
            (not (nil? c2x)) (set-tr :c2x :c2y)))]

    (mapv #(update % :params transform-params) content)))

(defn segments->content
  ([segments]
   (segments->content segments false))

  ([segments closed?]
   (let [initial (first segments)
         lines (rest segments)]

     (d/concat [{:command :move-to
                 :params (select-keys initial [:x :y])}]
               (->> lines
                    (mapv #(hash-map :command :line-to
                                     :params (select-keys % [:x :y]))))

               (when closed?
                 [{:command :close-path}])))))

(defonce num-segments 10)

(defn curve->lines
  "Transform the bezier curve given by the parameters into a series of straight lines
  defined by the constant num-segments"
  [start end h1 h2]
  (let [offset (/ 1 num-segments)
        tp (fn [t] (curve-values start end h1 h2 t))]
    (loop [from 0
           result []]

      (let [to (min 1 (+ from offset))
            line [(tp from) (tp to)]
            result (conj result line)]

        (if (>= to 1)
          result
          (recur to result))))))

(defn path->lines
  "Given a path returns a list of lines that approximate the path"
  [shape]
  (loop [command (first (:content shape))
         pending (rest (:content shape))
         result []
         last-start nil
         prev-point nil]

    (if-let [{:keys [command params]} command]
      (let [point (if (= :close-path command)
                    last-start
                    (gpt/point params))

            result (case command
                     :line-to  (conj result [prev-point point])
                     :curve-to (let [h1 (gpt/point (:c1x params) (:c1y params))
                                     h2 (gpt/point (:c2x params) (:c2y params))]
                                 (into result (curve->lines prev-point point h1 h2)))
                     :move-to  (cond-> result
                                 last-start (conj [prev-point last-start]))
                     result)
            last-start (if (= :move-to command)
                         point
                         last-start)
            ]
        (recur (first pending)
               (rest pending)
               result
               last-start
               point))

      (conj result [prev-point last-start]))))

(defonce path-closest-point-accuracy 0.01)
(defn curve-closest-point
  [position start end h1 h2]
  (let [d (memoize (fn [t] (gpt/distance position (curve-values start end h1 h2 t))))]
    (loop [t1 0
           t2 1]
      (if (<= (mth/abs (- t1 t2)) path-closest-point-accuracy)
        (-> (curve-values start end h1 h2 t1)
            ;; store the segment info
            (with-meta {:t t1 :from-p start :to-p end}))

        (let [ht  (+ t1 (/ (- t2 t1) 2))
              ht1 (+ t1 (/ (- t2 t1) 4))
              ht2 (+ t1 (/ (* 3 (- t2 t1)) 4))

              [t1 t2] (cond
                        (< (d ht1) (d ht2))
                        [t1 ht]

                        (< (d ht2) (d ht1))
                        [ht t2]

                        (and (< (d ht) (d t1)) (< (d ht) (d t2)))
                        [ht1 ht2]

                        (< (d t1) (d t2))
                        [t1 ht]

                        :else
                        [ht t2])]
          (recur t1 t2))))))

(defn line-closest-point
  "Point on line"
  [position from-p to-p]

  (let [e1 (gpt/to-vec from-p to-p )
        e2 (gpt/to-vec from-p position)

        len2 (+ (mth/sq (:x e1)) (mth/sq (:y e1)))
        t (/ (gpt/dot e1 e2) len2)]

    (if (and (>= t 0) (<= t 1) (not (mth/almost-zero? len2)))
      (-> (gpt/add from-p (gpt/scale e1 t))
          (with-meta {:t t
                      :from-p from-p
                      :to-p to-p}))

      ;; There is no perpendicular projection in the line so the closest
      ;; point will be one of the extremes
      (if (<= (gpt/distance position from-p) (gpt/distance position to-p))
        from-p
        to-p))))

(defn path-closest-point
  "Given a path and a position"
  [shape position]

  (let [point+distance
        (fn [[cur-cmd prev-cmd]]
          (let [from-p (command->point prev-cmd)
                to-p   (command->point cur-cmd)
                h1 (gpt/point (get-in cur-cmd [:params :c1x])
                              (get-in cur-cmd [:params :c1y]))
                h2 (gpt/point (get-in cur-cmd [:params :c2x])
                              (get-in cur-cmd [:params :c2y]))
                point
                (case (:command cur-cmd)
                  :line-to
                  (line-closest-point position from-p to-p)

                  :curve-to
                  (curve-closest-point position from-p to-p h1 h2)

                  nil)]
            (when point
              [point (gpt/distance point position)])))

        find-min-point (fn [[min-p min-dist :as acc] [cur-p cur-dist :as cur]]
                         (if (and (some? acc) (or (not cur) (<= min-dist cur-dist)))
                           [min-p min-dist]
                           [cur-p cur-dist]))]

    (->> (:content shape)
         (d/with-prev)
         (map point+distance)
         (reduce find-min-point)
         (first))))

(defn- get-line-tval
  [[{x1 :x y1 :y} {x2 :x y2 :y}] {:keys [x y]}]
  (if (mth/almost-zero? (- x2 x1))
    (/ (- y y1) (- y2 y1))
    (/ (- x x1) (- x2 x1))))

(defn- curve-range->rect
  [curve from-t to-t]

  (let [[from-p to-p :as curve] (subcurve-range curve from-t to-t)
        extremes (->> (curve-extremities curve)
                      (mapv #(curve-values curve %)))]
    (gpr/points->rect (into [from-p to-p] extremes))))


(defn line-line-crossing
  [[from-p1 to-p1 :as l1] [from-p2 to-p2 :as l2]]

  (let [{x1 :x y1 :y} from-p1
        {x2 :x y2 :y} to-p1

        {x3 :x y3 :y} from-p2
        {x4 :x y4 :y} to-p2

        nx (- (* (- x3 x4) (- (* x1 y2) (* y1 x2)))
              (* (- x1 x2) (- (* x3 y4) (* y3 x4))))

        ny (- (* (- y3 y4) (- (* x1 y2) (* y1 x2)))
              (* (- y1 y2) (- (* x3 y4) (* y3 x4))))

        d  (- (* (- x1 x2) (- y3 y4))
              (* (- y1 y2) (- x3 x4)))]

    (when-not (mth/almost-zero? d)
      ;; Coordinates in the line. We calculate the tvalue that will
      ;; return 0-1 as a percentage in the segment
      (let [cross-p (gpt/point (/ nx d) (/ ny d))
            t1 (get-line-tval l1 cross-p)
            t2 (get-line-tval l2 cross-p)]
        [t1 t2]))))

(defn line-curve-crossing
  [[from-p1 to-p1]
   [from-p2 to-p2 h1-p2 h2-p2]]

  (let [theta (-> (mth/atan2 (- (:y to-p1) (:y from-p1))
                             (- (:x to-p1) (:x from-p1)))
                  (mth/degrees))

        transform (-> (gmt/matrix)
                      (gmt/rotate (- theta))
                      (gmt/translate (gpt/negate from-p1)))

        c2' [(gpt/transform from-p2 transform)
             (gpt/transform to-p2 transform)
             (gpt/transform h1-p2 transform)
             (gpt/transform h2-p2 transform)]]

    (curve-roots c2' :y)))

(defn ray-line-intersect
  [point line]

  (let [ray-line [point (gpt/point (inc (:x point)) (:y point))]
        [ray-t line-t] (line-line-crossing ray-line line)]

    (when (and (some? line-t) (> ray-t 0) (>= line-t 0) (< line-t 1))
      [[(line-values line line-t)
        (line-windup line line-t)]])))

(defn line-line-intersect
  [l1 l2]

  (let [[l1-t l2-t] (line-line-crossing l1 l2)]
    (when (and (some? l1-t) (some? l2-t)
               (> l1-t 0.01) (< l1-t 0.99)
               (> l2-t 0.01) (< l2-t 0.99))
      [[l1-t] [l2-t]])))

(defn ray-curve-intersect
  [ray-line c2]

  (let [;; ray-line [point (gpt/point (inc (:x point)) (:y point))]
        curve-ts (->> (line-curve-crossing ray-line c2)
                      (filterv #(let [curve-v (curve-values c2 %)
                                      curve-tg (curve-tangent c2 %)
                                      curve-tg-angle (gpt/angle curve-tg)
                                      ray-t (get-line-tval ray-line curve-v)]
                                  (and (> ray-t 0)
                                       (> (mth/abs (- curve-tg-angle 180)) 0.01)
                                       (> (mth/abs (- curve-tg-angle 0)) 0.01)) )))]
    (->> curve-ts
         (mapv #(vector (curve-values c2 %)
                        (curve-windup c2 %))))))

(defn line-curve-intersect
  [l1 c2]
  (let [curve-ts (->> (line-curve-crossing l1 c2)
                      (filterv #(let [curve-v (curve-values c2 %)
                                      line-t (get-line-tval l1 curve-v)]
                                  (and (> line-t 0.001) (< line-t 0.999)))))
        ;; Intersection line-curve points
        intersect-ps (->> curve-ts
                          (mapv #(curve-values c2 %)))
        
        line-ts (->> intersect-ps
                     (mapv #(get-line-tval l1 %)))]
    [line-ts curve-ts]))

(defn curve-curve-intersect
  [c1 c2]

  (letfn [(remove-close-ts [ts]
            (loop [current (first ts)
                   pending (rest ts)
                   acc     nil
                   result  []]
              (if (nil? current)
                result
                (if (and (some? acc)
                         (< (mth/abs (- current acc)) 0.01))
                  (recur (first pending)
                         (rest pending)
                         acc
                         result)

                  (recur (first pending)
                         (rest pending)
                         current
                         (conj result current))))))

          (check-range [c1-from c1-to c2-from c2-to]
            (let [r1 (curve-range->rect c1 c1-from c1-to)
                  r2 (curve-range->rect c2 c2-from c2-to)]

              (when (gpr/overlaps-rects? r1 r2)
                (if (< (gpt/distance (curve-values c1 c1-from)
                                     (curve-values c2 c2-from))
                       curve-curve-precision)
                  [(sorted-set (mth/precision c1-from 4))
                   (sorted-set (mth/precision c2-from 4))]

                  (let [c1-half (+ c1-from (/ (- c1-to c1-from) 2))
                        c2-half (+ c2-from (/ (- c2-to c2-from) 2))

                        [c1-ts-1 c2-ts-1] (check-range c1-from c1-half c2-from c2-half)
                        [c1-ts-2 c2-ts-2] (check-range c1-from c1-half c2-half c2-to)
                        [c1-ts-3 c2-ts-3] (check-range c1-half c1-to c2-from c2-half)
                        [c1-ts-4 c2-ts-4] (check-range c1-half c1-to c2-half c2-to)]

                    [(into (sorted-set) (d/concat [] c1-ts-1 c1-ts-2 c1-ts-3 c1-ts-4))
                     (into (sorted-set) (d/concat [] c2-ts-1 c2-ts-2 c2-ts-3 c2-ts-4))])))))]

    (let [[c1-ts c2-ts] (check-range 0.005 0.995 0.005 0.995)
          c1-ts (remove-close-ts c1-ts)
          c2-ts (remove-close-ts c2-ts)]
      [c1-ts c2-ts])))

(defn curve->rect
  [[from-p to-p :as curve]]
  (let [extremes (->> (curve-extremities curve)
                      (mapv #(curve-values curve %)))]
    (gpr/points->rect (into [from-p to-p] extremes))))


(defn is-point-in-content?
  [point content]

  (letfn [(cast-ray [[cmd prev]]
            (let [ray-line [point (gpt/point (inc (:x point)) (:y point))]]
              (case (:command cmd)
                :line-to  (ray-line-intersect  point (command->line cmd (command->point prev)))
                :curve-to (ray-curve-intersect ray-line (command->bezier cmd (command->point prev)))
                #_:else   [])))]

    ;; non-zero windup rule
    (->> (d/with-prev content)
         (mapcat cast-ray)
         (map second)
         (reduce +)
         (not= 0))))

(defn split-line-to
  "Given a point and a line-to command will create a two new line-to commands
  that will split the original line into two given a value between 0-1"
  [from-p cmd t-val]
  (let [to-p (upc/command->point cmd)
        sp (gpt/lerp from-p to-p t-val)]
    [(upc/make-line-to sp) cmd]))

(defn split-curve-to
  "Given the point and a curve-to command will split the curve into two new
  curve-to commands given a value between 0-1"
  [from-p cmd t-val]
  (let [params (:params cmd)
        end (gpt/point (:x params) (:y params))
        h1 (gpt/point (:c1x params) (:c1y params))
        h2 (gpt/point (:c2x params) (:c2y params))
        [[_ to1 h11 h21]
         [_ to2 h12 h22]] (curve-split from-p end h1 h2 t-val)]
    [(upc/make-curve-to to1 h11 h21)
     (upc/make-curve-to to2 h12 h22)]))

(defn split-line-to-ranges
  "Splits a line into several lines given the points in `values`
  for example (split-line-to-ranges p c [0 0.25 0.5 0.75 1] will split
  the line into 4 lines"
  [from-p cmd values]
  (let [to-p (upc/command->point cmd)]
    (->> (conj values 1)
         (mapv (fn [val]
                 (-> (gpt/lerp from-p to-p val)
                     #_(gpt/round 2)
                     (upc/make-line-to)))))))

(defn split-curve-to-ranges
  "Splits a curve into several curves given the points in `values`
  for example (split-curve-to-ranges p c [0 0.25 0.5 0.75 1] will split
  the curve into 4 curves that draw the same curve"
  [from-p cmd values]
  (if (empty? values)
    [cmd]
    (let [to-p (upc/command->point cmd)
          params (:params cmd)
          h1 (gpt/point (:c1x params) (:c1y params))
          h2 (gpt/point (:c2x params) (:c2y params))

          values-set (->> (conj values 1) (into (sorted-set)))]
      (->> (d/with-prev values-set)
           (mapv
            (fn [[t1 t0]]
              (let [t0 (if (nil? t0) 0 t0)
                    [_ to-p h1' h2'] (subcurve-range from-p to-p h1 h2 t0 t1)]
                (upc/make-curve-to (-> to-p #_(gpt/round 2)) h1' h2'))))))))


(defn content-center
  [content]
  (-> content
      content->selrect
      gsc/center-selrect))

(defn content->points+selrect
  "Given the content of a shape, calculate its points and selrect"
  [shape content]
  (let [{:keys [flip-x flip-y]} shape
        transform
        (cond-> (:transform shape (gmt/matrix))
          flip-x (gmt/scale (gpt/point -1 1))
          flip-y (gmt/scale (gpt/point 1 -1)))

        transform-inverse
        (cond-> (gmt/matrix)
          flip-x (gmt/scale (gpt/point -1 1))
          flip-y (gmt/scale (gpt/point 1 -1))
          :always (gmt/multiply (:transform-inverse shape (gmt/matrix))))

        center (or (gsc/center-shape shape)
                   (content-center content))

        base-content (transform-content
                      content
                      (gmt/transform-in center transform-inverse))

        ;; Calculates the new selrect with points given the old center
        points (-> (content->selrect base-content)
                   (gpr/rect->points)
                   (gsc/transform-points center transform))

        points-center (gsc/center-points points)

        ;; Points is now the selrect but the center is different so we can create the selrect
        ;; through points
        selrect (-> points
                    (gsc/transform-points points-center transform-inverse)
                    (gpr/points->selrect))]
    [points selrect]))
