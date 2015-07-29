(ns clojure.core.matrix.impl.persistent-vector
  (:require [clojure.core.matrix.protocols :as mp]
            [clojure.core.matrix.implementations :as imp]
            [clojure.core.matrix.impl.mathsops :as mops]
            [clojure.core.matrix.multimethods :as mm]
            [clojure.core.matrix.utils :refer [scalar-coerce error doseq-indexed]])
  (:import [clojure.lang IPersistentVector Indexed]
           [java.util List]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

;; =======================================================================
;; utility functions for manipulating persistent vector matrices
;;
;; Format assumed to be a nested vector
;;
;; Vectors can contain other matrices to add an extra dimension to another implementation.
;; this is a powerful feature - it means we can do higher dimensional work with matrices
;; even if the underlying implementation does not natively support this
;;
;; However: this also imposes limitations, in the sense that Persistent Vector matrices
;; cannot hold other array types without considering them to be part of the array structure.
;; This means that a 2D matrix of 1D vectors gets treated as a single 3D matrix. This may
;; cause some surprising / undefined behaviours.

(declare persistent-vector-coerce)

(defn coerce-nested
  "Ensures a vector is fully coerced to nested persistent vectors"
  ([v]
    (mapv persistent-vector-coerce v)))

(defmacro vector-1d? [pv]
  `(let [pv# ^IPersistentVector ~pv]
     (or (== 0 (.length pv#)) (== 0 (mp/dimensionality (.nth pv# 0))))))

(defn- mapmatrix
  "Maps a function over all components of a persistent vector matrix. Like mapv but for matrices.
   Assumes correct dimensionality / shape.

   Returns a nested persistent vector matrix or a scalar value."
  ([f m]
    (let [dims (long (mp/dimensionality m))]
      (cond
        (== 0 dims) (f (scalar-coerce m))
        (== 1 dims) (mapv #(f (scalar-coerce %)) m)
        :else (mapv (partial mapmatrix f) m))))
  ([f m1 m2]
    (let [dim2 (long (mp/dimensionality m2))]
      (cond (mp/is-vector? m1)
        (do
          (when (> dim2 1) (error "mapping with array of higher dimensionality?"))
          (when (and (== 1 dim2) (not= (mp/dimension-count m1 0) (mp/dimension-count m2 0))) (error "Incompatible vector sizes"))
          (if (== 0 dim2)
            (let [v (scalar-coerce m2)] (mapv #(f % v) m1 ))
            (mapv f m1 (mp/element-seq m2))))
        :else
          (mapv (partial mapmatrix f)
                m1
                (mp/get-major-slice-seq m2)))))
  ([f m1 m2 & more]
    (if (mp/is-vector? m1)
      (apply mapv f m1 m2 more)
      (apply mapv (partial mapmatrix f) m1 m2 more))))

(defn- mapv-identity-check
  "Maps a function over a persistent vector, only modifying the vector if the function
   returns a different value"
  ([f ^IPersistentVector v]
    (let [n (.count v)]
      (loop [i 0 v v]
        (if (< i n)
          (let [x (.nth v i)
                y (f x)]
            (recur (inc i) (if (identical? x y) v (assoc v i y))))
          v)))))

(defn- check-vector-shape
  ([v shape]
    (and
      (instance? IPersistentVector v)
      (== (count v) (first shape))
      (if-let [ns (next shape)]
        (every? #(check-vector-shape % ns) v)
        (every? #(not (instance? IPersistentVector %)) v)))))

(defn is-nested-persistent-vectors?
  "Test if array is already in nested persistent vector array format."
  ([x]
    (cond
      (number? x) true
      (mp/is-scalar? x) true
      (not (instance? IPersistentVector x)) false
      :else (and
              (every? is-nested-persistent-vectors? x)
              (check-vector-shape x (mp/get-shape x))))))

(defn persistent-vector-coerce
  "Coerces to nested persistent vectors"
  [x]
  (let [dims (long (mp/dimensionality x))]
    (cond
      (> dims 0) (mp/convert-to-nested-vectors x) ;; any array with 1 or more dimensions
      (and (== dims 0) (not (mp/is-scalar? x))) (mp/get-0d x) ;; array with zero dimensionality

      ;; it's not an array - so try alternative coercions
      (nil? x) x
      (.isArray (class x)) (map persistent-vector-coerce (seq x))
      (instance? List x) (coerce-nested x)
      (instance? Iterable x) (coerce-nested x)
      (sequential? x) (coerce-nested x)

      ;; treat as a scalar value
      :default x)))

(defn vector-dimensionality
  "Calculates the dimensionality (== nesting depth) of nested persistent vectors"
  [m]
  (cond
    (clojure.core/vector? m)
      (if (> (count m) 0)
        (+ 1 (vector-dimensionality (.nth ^IPersistentVector m 0)))
        1)
    :else (mp/dimensionality m)))

;; =======================================================================
;; Implementation for nested Clojure persistent vectors used as matrices


(extend-protocol mp/PImplementation
  IPersistentVector
    (implementation-key [m] :persistent-vector)
    (meta-info [m]
      {:doc "Implementation for nested Clojure persistent vectors
             used as matrices"})
    (new-vector [m length] (vec (repeat length 0.0)))
    (new-matrix [m rows columns] (vec (repeat rows (mp/new-vector m columns))))
    (new-matrix-nd [m dims]
      (if-let [dims (seq dims)]
        (vec (repeat (first dims) (mp/new-matrix-nd m (next dims))))
        0.0))
    (construct-matrix [m data]
      (persistent-vector-coerce data))
    (supports-dimensionality? [m dims]
      true))

(extend-protocol mp/PBroadcast
  IPersistentVector
    (broadcast [m target-shape]
      (let [mshape (mp/get-shape m)
            dims (long (count mshape))
            tdims (long (count target-shape))]
        (cond
          (> dims tdims)
            (error "Can't broadcast to a lower dimensional shape")
          (not (every? identity (map #(== %1 %2) mshape (take-last dims target-shape))))
            (error "Incompatible shapes, cannot broadcast shape " (vec mshape) " to " (vec target-shape))
          :else
            (reduce
              (fn [m dup] (vec (repeat dup m)))
              m
              (reverse (drop-last dims target-shape)))))))

(extend-protocol mp/PBroadcastLike
  IPersistentVector
    (broadcast-like [m a]
      (mp/broadcast a (mp/get-shape m))))

(extend-protocol mp/PBroadcastCoerce
  IPersistentVector
    (broadcast-coerce [m a]
      (mp/broadcast (persistent-vector-coerce a) (mp/get-shape m))))

(extend-protocol mp/PIndexedAccess
  IPersistentVector
    (get-1d [m x]
      (let [r (.nth m (int x))]
        (scalar-coerce r)))
    (get-2d [m x y]
      (let [row (.nth m (int x))]
        (mp/get-1d row y)))
    (get-nd [m indexes]
      (if-let [indexes (seq indexes)]
        (if-let [next-indexes (next indexes)]
          (let [m (.nth m (int (first indexes)))]
            (mp/get-nd m next-indexes))
          (.nth m (int (first indexes))))
        m)))

;; we extend this so that nested mutable implementions are possible
(extend-protocol mp/PIndexedSetting
  IPersistentVector
    (set-1d [m row v]
      (assoc m row v))
    (set-2d [m row column v]
      (assoc m row (mp/set-1d (m row) column v)))
    (set-nd [m indexes v]
      (if-let [indexes (seq indexes)]
        (let [fi (first indexes)]
          (if (== 1 (count indexes))
              (assoc m fi v)
              (assoc m fi (mp/set-nd (m fi) (next indexes) v))))
        (error "Trying to set on a persistent vector with insufficient indexes?")))
    (is-mutable? [m]
      false))

(extend-protocol mp/PMatrixSlices
  IPersistentVector
    (get-row [m i]
      (.nth m (long i)))
    (get-column [m i]
      (mp/get-slice m 1 i))
    (get-major-slice [m i]
      (let [sl (.nth m (long i))]
        sl))
    (get-slice [m dimension i]
      (let [dimension (long dimension)]
        (if (== dimension 0)
          (mp/get-major-slice m i)
          (let [sd (dec dimension)]
            (mapv #(mp/get-slice % sd i) m))))))

(extend-protocol mp/PMatrixRows
  IPersistentVector
	  (get-rows [m]
      (seq m)))

(extend-protocol mp/PSliceView
  IPersistentVector
    (get-major-slice-view [m i] (.nth m i)))

(extend-protocol mp/PSliceSeq
  IPersistentVector
    (get-major-slice-seq [m]
      (if (vector-1d? m)
        (seq (map mp/get-0d m))
        (seq m))))

(extend-protocol mp/PSliceJoin
  IPersistentVector
    (join [m a]
      (let [dims (mp/dimensionality m)
            adims (mp/dimensionality a)]
        (cond
          (== dims adims)
            (vec (concat (mp/get-major-slice-seq m) (mp/get-major-slice-seq a)))
          (== dims (inc adims))
            (conj m a)
          :else
            (error "Joining with array of incompatible size")))))

(extend-protocol mp/PRotate
  IPersistentVector
    (rotate [m dim places]
      (if (== 0 dim)
        (let [c (count m)
              sh (if (> c 0) (mod places c) 0)]
          (if (== sh 0)
            m
            (vec (concat (subvec m sh c) (subvec m 0 sh)))))
        (mapv (fn [s] (mp/rotate s (dec dim) places)) m))))

(extend-protocol mp/POrder
  IPersistentVector
  (order
    ([m indices]
      (mapv #(nth m %) (mp/element-seq indices)))
    ([m dimension indices]
      (if (== dimension 0)
        (mp/order m indices)
        (mapv #(mp/order % (dec dimension) indices) m)))))

(extend-protocol mp/PSubVector
  IPersistentVector
    (subvector [m start length]
      (subvec m start (+ start length))))

(extend-protocol mp/PValidateShape
  IPersistentVector
    (validate-shape [m]
      (if (mp/same-shapes? m)
        (mp/get-shape m)
        (error "Inconsistent shape for persistent vector array."))))

(extend-protocol mp/PMatrixAdd
  IPersistentVector
    (matrix-add [m a]
      (let [[m a] (mp/broadcast-compatible m a)]
        (mapmatrix + m (persistent-vector-coerce a))))
    (matrix-sub [m a]
      (let [[m a] (mp/broadcast-compatible m a)]
        (mapmatrix - m (persistent-vector-coerce a)))))

(extend-protocol mp/PVectorOps
  IPersistentVector
    (vector-dot [a b]
      (let [dims (long (mp/dimensionality b))
            ;; b (persistent-vector-coerce b)
            ]
        (cond
          (and (== dims 1) (instance? Indexed b) (== 1 (mp/dimensionality b)))
            (let [ca (count a)
                  cb (count b)]
              (when-not (== ca cb) (error "Mismatched vector sizes"))
              (loop [i (long 0) res 0.0]
                (if (>= i ca)
                  res
                  (recur (inc i) (+ res (* (nth a i) (nth b i)))))))
          (== dims 0) (mp/scale a b)
          :else (mp/inner-product a b))))
    (length [a]
      (Math/sqrt (double (reduce + (map #(* % %) a)))))
    (length-squared [a]
      (reduce + (map #(* % %) a)))
    (normalise [a]
      (mp/scale a (/ 1.0 (Math/sqrt (mp/length-squared a))))))

(extend-protocol mp/PMutableMatrixConstruction
  IPersistentVector
    (mutable-matrix [m]
      nil ;; fall-though: should get an ndarray result
      ))

(extend-protocol mp/PImmutableMatrixConstruction
  IPersistentVector
  (immutable-matrix [m]
    m))

(extend-protocol mp/PVectorDistance
  IPersistentVector
    (distance [a b] (mp/length (mapv - b a))))

(extend-protocol mp/PSummable
  IPersistentVector
    (element-sum [a]
      (mp/element-reduce a +)))

(extend-protocol mp/PCoercion
  IPersistentVector
    (coerce-param [m param]
      (persistent-vector-coerce param)))

(extend-protocol mp/PMatrixEquality
  IPersistentVector
    (matrix-equals [a b]
      (let [bdims (long (mp/dimensionality b))]
        (cond
          (<= bdims 0)
            false
          (not= (count a) (mp/dimension-count b 0))
            false
          (== 1 bdims)
            (and (== 1 (mp/dimensionality a))
                 (let [n (long (count a))]
                   (loop [i 0]
                     (if (< i n)
                       (if (== (mp/get-1d a i) (mp/get-1d b i))
                         (recur (inc i))
                         false)
                       true))))
          (vector? b)
            (let [n (long (count a))]
               (loop [i 0]
                     (if (< i n)
                       (if (mp/matrix-equals (a i) (b i))
                         (recur (inc i))
                         false)
                       true)))
          :else
            (loop [sa (seq a) sb (mp/get-major-slice-seq b)]
              (if sa
                (if (mp/matrix-equals (first sa) (first sb))
                  (recur (next sa) (next sb))
                  false)
                true))))))

(extend-protocol mp/PMatrixMultiply
  IPersistentVector
    (element-multiply [m a]
      (if (number? a)
        (mp/scale m a)
        (let [[m a] (mp/broadcast-compatible m a)]
          (mp/element-map m * a))))
    (matrix-multiply [m a]
      (let [mdims (long (mp/dimensionality m))
            adims (long (mp/dimensionality a))]
        (cond
          (== adims 0) (mp/scale m a)
          (and (== mdims 1) (== adims 2))
            (vec (for [i (range (mp/dimension-count a 1))]
                     (let [r (mp/get-column a i)]
                       (mp/vector-dot m r))))
          (and (== mdims 2) (== adims 1))
            (mapv #(mp/vector-dot % a) m)
          (and (== mdims 2) (== adims 2))
            (mapv (fn [r]
                     (vec (for [j (range (mp/dimension-count a 1))]
                            (mp/vector-dot r (mp/get-column a j))))) m)
          :else
            (mm/mul m a)))))

(extend-protocol mp/PVectorTransform
  IPersistentVector
    (vector-transform [m a]
      (mp/matrix-multiply m a))
    (vector-transform! [m a]
      (mp/assign! a (mp/matrix-multiply m a))))

(extend-protocol mp/PMatrixScaling
  IPersistentVector
    (scale [m a]
      (let [a (mp/get-0d a)]
        (mapmatrix #(* % a) m)))
    (pre-scale [m a]
      (let [a (mp/get-0d a)]
        (mapmatrix #(* a %) m))))

(extend-protocol mp/PSquare
  IPersistentVector
    (square [m]
      (mapmatrix #(* % %) m)))

(extend-protocol mp/PRowOperations
  IPersistentVector
    (swap-rows [m i j]
      (if (== i j)
        m
        (assoc (assoc m i (m j)) j (m i))))
    (multiply-row [m i factor]
      (assoc m i (mp/scale (m i) factor)))
    (add-row [m i j factor]
      (assoc m i (mp/matrix-add (m i) (mp/matrix-multiply (m j) factor)))))

;; helper functin to build generic maths operations
(defn build-maths-function
  ([[name func]]
    `(~name [~'m]
            (mapmatrix (fn [x#] (double (~func (double x#)))) ~'m))))

;; code generation for maths functions
;; we generate both name and name! versions
(eval
  `(extend-protocol mp/PMathsFunctions
     IPersistentVector
       ~@(map build-maths-function mops/maths-ops)
       ~@(map (fn [[name func]]
                (let [name (str name "!")
                      mname (symbol name)
                      mpmname (symbol "clojure.core.matrix.protocols" name)]
                  `(~mname [m#]
                     (doseq [s# (mp/get-major-slice-seq m#)]
                       (~mpmname s#)))))
              mops/maths-ops)))

(extend-protocol mp/PDimensionInfo
  IPersistentVector
    (dimensionality [m]
      (if (== 0 (.length m))
        1
        (inc (mp/dimensionality (.nth m 0)))))
    (is-vector? [m]
      (vector-1d? m))
    (is-scalar? [m]
      false)
    (get-shape [m]
      (let [c (.length m)]
        (cons c (if (> c 0)
                  (mp/get-shape (m 0))
                  nil))))
    (dimension-count [m x]
      (let [x (long x)]
        (if (== x 0)
          (.length m)
          (mp/dimension-count (m 0) (dec x))))))

(extend-protocol mp/PElementCount
  IPersistentVector
    (element-count [m]
      (let [c (long (count m))]
        (if (== c 0)
          0
          (* c (mp/element-count (m 0)))))))

;; we need to implement this for all persistent vectors since we need to check all nested components
(extend-protocol mp/PConversion
  IPersistentVector
    (convert-to-nested-vectors [m]
      (if (is-nested-persistent-vectors? m)
        m
        (let [m (mapv-identity-check mp/convert-to-nested-vectors m)
              m-shapes (map mp/get-shape m)]
          (if (every? (partial = (first m-shapes)) (rest m-shapes))
            m
            (error "Can't convert to persistent vector array: inconsistent shape."))))))

(defn- copy-to-double-array [m ^doubles arr ^long off ^long size]
  (let [ct (count m)]
    (cond
      (not (vector? m))
        (doseq-indexed [v (mp/element-seq m) i]
          (aset arr (+ off i) (double v)))
      (and (== size ct) (not (vector? (nth m 0 nil))))
        (dotimes [i size]
          (aset arr (+ off i) (double (.nth ^IPersistentVector m i))))
      :else
        (let [skip (quot size ct)]
          (dotimes [i ct]
            (copy-to-double-array (nth m i) arr (+ off (* i skip)) skip))))
    arr))

(extend-protocol mp/PDoubleArrayOutput
  IPersistentVector
    (to-double-array [m]
      (let [size (long (mp/element-count m))
            arr (double-array size)
            ct (count m)]
        (copy-to-double-array m arr 0 size)
        arr))
    (as-double-array [m] nil))

(defn- copy-to-object-array [m ^objects arr ^long off ^long size]
  (let [ct (count m)]
    (cond
      (not (vector? m))
        (doseq-indexed [v (mp/element-seq m) i]
          (aset arr (+ off i) v))
      (and (== size ct) (not (vector? (nth m 0 nil))))
        (dotimes [i size]
          (aset arr (+ off i) (.nth ^IPersistentVector m i)))
      :else
        (let [skip (quot size ct)]
          (dotimes [i ct]
            (copy-to-object-array (nth m i) arr (+ off (* i skip)) skip))))
    arr))

(extend-protocol mp/PObjectArrayOutput
  IPersistentVector
    (to-object-array [m]
      (let [size (long (mp/element-count m))
            arr (object-array size)
            ct (count m)]
        (copy-to-object-array m arr 0 size)
        arr))
    (as-object-array [m] nil))

(extend-protocol mp/PFunctionalOperations
  IPersistentVector
    (element-seq [m]
      (cond
        (== 0 (count m))
          '()
        (> (mp/dimensionality (m 0)) 0)
          (mapcat mp/element-seq m)
        :else
          (map mp/get-0d m)))
    (element-map
      ([m f]
        (mapmatrix f m))
      ([m f a]
        (mapmatrix f m (mp/broadcast-like m a)))
      ([m f a more]
        (apply mapmatrix f m a more)))
    (element-map!
      ([m f]
        (doseq [s m]
          (mp/element-map! s f))
        m)
      ([m f a]
        (dotimes [i (count m)]
          (mp/element-map! (m i) f (mp/get-major-slice a i)))
        m)
      ([m f a more]
        (dotimes [i (count m)]
          (apply mp/element-map! (m i) f (mp/get-major-slice a i) (map #(mp/get-major-slice % i) more)))
        m))
    (element-reduce
      ([m f]
        (reduce f (mp/element-seq m)))
      ([m f init]
        (reduce f init (mp/element-seq m)))))

(extend-protocol mp/PMapIndexed
  IPersistentVector
    (element-map-indexed
      ([ms f]
       (let [dims (long (mp/dimensionality ms))]
         (cond
           (== 0 dims) (f [] (scalar-coerce ms))
           (== 1 dims) (vec (for [i (range (count ms))]
                              (f [i] (nth ms i))))
           :else       (vec (for [i (range (count ms))]
                              (mp/element-map-indexed (nth ms i) #(f (cons i %1) %2)))))))
      ([ms f as]
       (let [as   (mp/broadcast-like ms as)
             dima (long (mp/dimensionality as))]
         (if (mp/is-vector? ms)
           (do
             (when (> dima 1)
               (error "mapping with array of higher dimensionality?"))
             (when (and (== 1 dima)
                        (not= (mp/dimension-count ms 0) (mp/dimension-count as 0)))
               (error "Incompatible vector sizes"))
             (if (== 0 dima)
               (let [v (scalar-coerce as)]
                 (mapv #(f [%1] %2 v) (range (count ms))) ms)
               (mapv #(apply f [%1] %&) (range (count ms)) ms (mp/element-seq as))))
           (mapv (fn [i m a] (mp/element-map-indexed m #(apply f (cons i %1) %&) a))
                 (range (count ms)) ms (mp/get-major-slice-seq as)))))
      ([ms f as more]
       (if (mp/is-vector? ms)
         (apply mapv #(apply f [%1] %&) (range (count ms)) ms as more)
         (apply mapv (fn [i m a & mr]
                       (mp/element-map-indexed m #(apply f (cons i %1) %&) a mr))
                     (range (count ms)) ms as more))))
    (element-map-indexed!
      ([m f]
        (dotimes [i (count m)]
          (mp/element-map-indexed! (m i) #(f (cons i %1) %2)))
        m)
      ([m f a]
        (dotimes [i (count m)]
          (mp/element-map-indexed! (m i) #(apply f (cons i %1) %&)
                                   (mp/get-major-slice a i)))
        m)
      ([m f a more]
        (dotimes [i (count m)]
          (apply mp/element-map-indexed! (m i) #(apply f (cons i %1) %&)
                 (mp/get-major-slice a i) (map #(mp/get-major-slice % i) more)))
        m))
  )

;; =====================================
;; Register implementation

(imp/register-implementation [1])
