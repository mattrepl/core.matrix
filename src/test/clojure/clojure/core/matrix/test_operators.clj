(ns clojure.core.matrix.test-operators
  (:refer-clojure :exclude [* - + / == min max])
  (:require clojure.core.matrix.impl.persistent-vector
            [clojure.core.matrix :refer :all]
            [clojure.core.matrix.operators :refer :all]
            [clojure.test :refer :all]))

;; Tests for the clojure.core.matrix.operators namespace

(deftest test-multiply
  (testing "scalars"
    (is (== 6 (* 3 2)))
    (is (== 6 (e* 3 2))))
  (testing "matrix scaling"
    (is (= [6] (* [3] 2)))
    (is (= [6] (* 2 [3])))
    (is (= [[6]] (* 2 [[3]])))
    (is (= [[6]] (* [[2]] 3))))
  (testing "element-wise multiplication"
    (is (= [2 4] (e* [1 2] [2 2])))
    (is (= [[[2 4]]] (e* [[[1 2]]] [[[2 2]]])))))

(deftest test-maths-ops
  (testing "scalars"
    (is (== 1 (signum 2.5)))
    (is (== 1.0 (round 0.8))))
  (testing "matrices"
    (is (= [1.0] (signum [1.3])))
    (is (= [-1.0] (signum [-100]))))
  (testing "nested matrices"
    (is (= [[[1.0]]] (exp [[[0.0]]])))
    (is (= [[0.0]] (sin [[0.0]])))))

(deftest test-subtraction
  (testing "scalars"
    (is (== 5 (- 7 2)))
    (is (== 6 (- 10 2 2))))
  (testing "matrix subtraction"
    (is (= [1.0] (- [3.0] [2.0])))
    (is (= [[8.0]] (- [[12.0]] [[4.0]])))
    (is (= [[[8.0]]] (- [[[12.0]]] [[[4.0]]])))))

(deftest test-addition
  (testing "scalars"
    (is (== 5 (+ 3 2)))
    (is (== 6 (- 10 (+ 2 2)))))
  (testing "matrix addition"
    (is (= [3.0] (+ [1.0] [2.0])))
    (is (= [[8.0]] (+ [[3.0]] [[5.0]])))
    (is (= [[[8]]] (+ [[[2]]] [[[6]]])))))
