;; Licensed to the Apache Software Foundation (ASF) under one
;; or more contributor license agreements.  See the NOTICE file
;; distributed with this work for additional information
;; regarding copyright ownership.  The ASF licenses this file
;; to you under the Apache License, Version 2.0 (the
;; "License"); you may not use this file except in compliance
;; with the License.  You may obtain a copy of the License at
;;
;; http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns integration.org.apache.storm.integration-test
  (:use [clojure test])
  (:import [org.apache.storm Config])
  (:import [org.apache.storm.topology TopologyBuilder])
  (:import [org.apache.storm.generated InvalidTopologyException SubmitOptions TopologyInitialStatus RebalanceOptions])
  (:import [org.apache.storm.testing TestWordCounter TestWordSpout TestGlobalCount
            TestAggregatesCounter TestConfBolt AckFailMapTracker AckTracker TestPlannerSpout])
  (:import [org.apache.storm.task WorkerTopologyContext])
  (:import [org.apache.storm.utils Time])
  (:import [org.apache.storm.tuple Fields])
  (:import [org.mockito Mockito])
  (:use [org.apache.storm testing config clojure util])
  (:use [org.apache.storm.daemon common])
  (:require [org.apache.storm [cluster :as cluster]])
  (:require [org.apache.storm.daemon [executor :as executor]])
  (:require [org.apache.storm [thrift :as thrift]]))

(deftest test-basic-topology
  (doseq [zmq-on? [true false]]
    (with-simulated-time-local-cluster [cluster :supervisors 4
                                        :daemon-conf {STORM-LOCAL-MODE-ZMQ zmq-on?}]
      (let [topology (thrift/mk-topology
                      {"1" (thrift/mk-spout-spec (TestWordSpout. true) :parallelism-hint 3)}
                      {"2" (thrift/mk-bolt-spec {"1" ["word"]} (TestWordCounter.) :parallelism-hint 4)
                       "3" (thrift/mk-bolt-spec {"1" :global} (TestGlobalCount.))
                       "4" (thrift/mk-bolt-spec {"2" :global} (TestAggregatesCounter.))
                       })
            results (complete-topology cluster
                                       topology
                                       :mock-sources {"1" [["nathan"] ["bob"] ["joey"] ["nathan"]]}
                                       :storm-conf {TOPOLOGY-WORKERS 2
                                                    TOPOLOGY-TESTING-ALWAYS-TRY-SERIALIZE true})]
        (is (ms= [["nathan"] ["bob"] ["joey"] ["nathan"]]
                 (read-tuples results "1")))
        (is (ms= [["nathan" 1] ["nathan" 2] ["bob" 1] ["joey" 1]]
                 (read-tuples results "2")))
        (is (= [[1] [2] [3] [4]]
               (read-tuples results "3")))
        (is (= [[1] [2] [3] [4]]
               (read-tuples results "4")))
        ))))

(defbolt emit-task-id ["tid"] {:prepare true}
  [conf context collector]
  (let [tid (.getThisTaskIndex context)]
    (bolt
      (execute [tuple]
        (emit-bolt! collector [tid] :anchor tuple)
        (ack! collector tuple)
        ))))

(deftest test-multi-tasks-per-executor
  (with-simulated-time-local-cluster [cluster :supervisors 4]
    (let [topology (thrift/mk-topology
                    {"1" (thrift/mk-spout-spec (TestWordSpout. true))}
                    {"2" (thrift/mk-bolt-spec {"1" :all} emit-task-id
                      :parallelism-hint 3
                      :conf {TOPOLOGY-TASKS 6})
                     })
          results (complete-topology cluster
                                     topology
                                     :mock-sources {"1" [["a"]]})]
      (is (ms= [[0] [1] [2] [3] [4] [5]]
               (read-tuples results "2")))
      )))

(defbolt ack-every-other {} {:prepare true}
  [conf context collector]
  (let [state (atom -1)]
    (bolt
      (execute [tuple]
        (let [val (swap! state -)]
          (when (pos? val)
            (ack! collector tuple)
            ))))))

(defn assert-loop 
([afn ids] (assert-loop afn ids 10))
([afn ids timeout-secs]
  (loop [remaining-time (* timeout-secs 1000)]
    (let [start-time (System/currentTimeMillis)
          assertion-is-true (every? afn ids)]
      (if (or assertion-is-true (neg? remaining-time))
        (is assertion-is-true)
        (do
          (Thread/sleep 1)
          (recur (- remaining-time (- (System/currentTimeMillis) start-time)))
        ))))))

(defn assert-acked [tracker & ids]
  (assert-loop #(.isAcked tracker %) ids))

(defn assert-failed [tracker & ids]
  (assert-loop #(.isFailed tracker %) ids))

(deftest test-timeout
  (with-simulated-time-local-cluster [cluster :daemon-conf {TOPOLOGY-ENABLE-MESSAGE-TIMEOUTS true}]
    (let [feeder (feeder-spout ["field1"])
          tracker (AckFailMapTracker.)
          _ (.setAckFailDelegate feeder tracker)
          topology (thrift/mk-topology
                     {"1" (thrift/mk-spout-spec feeder)}
                     {"2" (thrift/mk-bolt-spec {"1" :global} ack-every-other)})]      
      (submit-local-topology (:nimbus cluster)
                             "timeout-tester"
                             {TOPOLOGY-MESSAGE-TIMEOUT-SECS 10}
                             topology)
      (.feed feeder ["a"] 1)
      (.feed feeder ["b"] 2)
      (.feed feeder ["c"] 3)
      (advance-cluster-time cluster 9)
      (assert-acked tracker 1 3)
      (is (not (.isFailed tracker 2)))
      (advance-cluster-time cluster 12)
      (assert-failed tracker 2)
      )))

(defbolt extend-timeout-twice {} {:prepare true}
  [conf context collector]
  (let [state (atom -1)]
    (bolt
      (execute [tuple]
        (do
          (Time/sleep (* 8 1000))
          (reset-timeout! collector tuple)
          (Time/sleep (* 8 1000))
          (reset-timeout! collector tuple)
          (Time/sleep (* 8 1000))
          (ack! collector tuple)
        )))))

(deftest test-reset-timeout
  (with-simulated-time-local-cluster [cluster :daemon-conf {TOPOLOGY-ENABLE-MESSAGE-TIMEOUTS true}]
    (let [feeder (feeder-spout ["field1"])
          tracker (AckFailMapTracker.)
          _ (.setAckFailDelegate feeder tracker)
          topology (thrift/mk-topology
                     {"1" (thrift/mk-spout-spec feeder)}
                     {"2" (thrift/mk-bolt-spec {"1" :global} extend-timeout-twice)})]
    (submit-local-topology (:nimbus cluster)
                           "timeout-tester"
                           {TOPOLOGY-MESSAGE-TIMEOUT-SECS 10}
                           topology)
    (advance-cluster-time cluster 11)
    (.feed feeder ["a"] 1)
    (advance-cluster-time cluster 21)
    (is (not (.isFailed tracker 1)))
    (is (not (.isAcked tracker 1)))
    (advance-cluster-time cluster 5)
    (assert-acked tracker 1)
    )))

(defn mk-validate-topology-1 []
  (thrift/mk-topology
                    {"1" (thrift/mk-spout-spec (TestWordSpout. true) :parallelism-hint 3)}
                    {"2" (thrift/mk-bolt-spec {"1" ["word"]} (TestWordCounter.) :parallelism-hint 4)}))

(defn mk-invalidate-topology-1 []
  (thrift/mk-topology
                    {"1" (thrift/mk-spout-spec (TestWordSpout. true) :parallelism-hint 3)}
                    {"2" (thrift/mk-bolt-spec {"3" ["word"]} (TestWordCounter.) :parallelism-hint 4)}))

(defn mk-invalidate-topology-2 []
  (thrift/mk-topology
                    {"1" (thrift/mk-spout-spec (TestWordSpout. true) :parallelism-hint 3)}
                    {"2" (thrift/mk-bolt-spec {"1" ["non-exists-field"]} (TestWordCounter.) :parallelism-hint 4)}))

(defn mk-invalidate-topology-3 []
  (thrift/mk-topology
                    {"1" (thrift/mk-spout-spec (TestWordSpout. true) :parallelism-hint 3)}
                    {"2" (thrift/mk-bolt-spec {["1" "non-exists-stream"] ["word"]} (TestWordCounter.) :parallelism-hint 4)}))

(defn try-complete-wc-topology [cluster topology]
  (try (do
         (complete-topology cluster
                            topology
                            :mock-sources {"1" [["nathan"] ["bob"] ["joey"] ["nathan"]]}
                            :storm-conf {TOPOLOGY-WORKERS 2})
         false)
       (catch InvalidTopologyException e true)))

(deftest test-validate-topology-structure
  (with-simulated-time-local-cluster [cluster :supervisors 4]
    (let [any-error1? (try-complete-wc-topology cluster (mk-validate-topology-1))
          any-error2? (try-complete-wc-topology cluster (mk-invalidate-topology-1))
          any-error3? (try-complete-wc-topology cluster (mk-invalidate-topology-2))
          any-error4? (try-complete-wc-topology cluster (mk-invalidate-topology-3))]
      (is (= any-error1? false))
      (is (= any-error2? true))
      (is (= any-error3? true))
      (is (= any-error4? true)))))

(defbolt identity-bolt ["num"]
  [tuple collector]
  (emit-bolt! collector (.getValues tuple) :anchor tuple)
  (ack! collector tuple))

(deftest test-system-stream
  ;; this test works because mocking a spout splits up the tuples evenly among the tasks
  (with-simulated-time-local-cluster [cluster]
      (let [topology (thrift/mk-topology
                      {"1" (thrift/mk-spout-spec (TestWordSpout. true) :p 3)}
                      {"2" (thrift/mk-bolt-spec {"1" ["word"] ["1" "__system"] :global} identity-bolt :p 1)
                       })
            results (complete-topology cluster
                                       topology
                                       :mock-sources {"1" [["a"] ["b"] ["c"]]}
                                       :storm-conf {TOPOLOGY-WORKERS 2})]
        (is (ms= [["a"] ["b"] ["c"] ["startup"] ["startup"] ["startup"]]
                 (read-tuples results "2")))
        )))

(defn ack-tracking-feeder [fields]
  (let [tracker (AckTracker.)]
    [(doto (feeder-spout fields)
       (.setAckFailDelegate tracker))
     (fn [val]
       (is (= (.getNumAcks tracker) val))
       (.resetNumAcks tracker)
       )]
    ))

(defbolt branching-bolt ["num"]
  {:params [amt]}
  [tuple collector]
  (doseq [i (range amt)]
    (emit-bolt! collector [i] :anchor tuple))
  (ack! collector tuple))

(defbolt agg-bolt ["num"] {:prepare true :params [amt]}
  [conf context collector]
  (let [seen (atom [])]
    (bolt
      (execute [tuple]
        (swap! seen conj tuple)
        (when (= (count @seen) amt)
          (emit-bolt! collector [1] :anchor @seen)
          (doseq [s @seen]
            (ack! collector s))
          (reset! seen [])
          )))
      ))

(defbolt ack-bolt {}
  [tuple collector]
  (ack! collector tuple))

(deftest test-acking
  (with-tracked-cluster [cluster]
    (let [[feeder1 checker1] (ack-tracking-feeder ["num"])
          [feeder2 checker2] (ack-tracking-feeder ["num"])
          [feeder3 checker3] (ack-tracking-feeder ["num"])
          tracked (mk-tracked-topology
                   cluster
                   (topology
                     {"1" (spout-spec feeder1)
                      "2" (spout-spec feeder2)
                      "3" (spout-spec feeder3)}
                     {"4" (bolt-spec {"1" :shuffle} (branching-bolt 2))
                      "5" (bolt-spec {"2" :shuffle} (branching-bolt 4))
                      "6" (bolt-spec {"3" :shuffle} (branching-bolt 1))
                      "7" (bolt-spec
                            {"4" :shuffle
                            "5" :shuffle
                            "6" :shuffle}
                            (agg-bolt 3))
                      "8" (bolt-spec {"7" :shuffle} (branching-bolt 2))
                      "9" (bolt-spec {"8" :shuffle} ack-bolt)}
                     ))]
      (submit-local-topology (:nimbus cluster)
                             "acking-test1"
                             {}
                             (:topology tracked))
      (.feed feeder1 [1])
      (tracked-wait tracked 1)
      (checker1 0)
      (.feed feeder2 [1])
      (tracked-wait tracked 1)
      (checker1 1)
      (checker2 1)
      (.feed feeder1 [1])
      (tracked-wait tracked 1)
      (checker1 0)
      (.feed feeder1 [1])
      (tracked-wait tracked 1)
      (checker1 1)
      (.feed feeder3 [1])
      (tracked-wait tracked 1)
      (checker1 0)
      (checker3 0)
      (.feed feeder2 [1])
      (tracked-wait tracked 1)
      (checker1 1)
      (checker2 1)
      (checker3 1)
      
      )))

(deftest test-ack-branching
  (with-tracked-cluster [cluster]
    (let [[feeder checker] (ack-tracking-feeder ["num"])
          tracked (mk-tracked-topology
                   cluster
                   (topology
                     {"1" (spout-spec feeder)}
                     {"2" (bolt-spec {"1" :shuffle} identity-bolt)
                      "3" (bolt-spec {"1" :shuffle} identity-bolt)
                      "4" (bolt-spec
                            {"2" :shuffle
                             "3" :shuffle}
                             (agg-bolt 4))}))]
      (submit-local-topology (:nimbus cluster)
                             "test-acking2"
                             {}
                             (:topology tracked))
      (.feed feeder [1])
      (tracked-wait tracked 1)
      (checker 0)
      (.feed feeder [1])
      (tracked-wait tracked 1)
      (checker 2)
      )))

(defbolt dup-anchor ["num"]
  [tuple collector]
  (emit-bolt! collector [1] :anchor [tuple tuple])
  (ack! collector tuple))

(def bolt-prepared? (atom false))
(defbolt prepare-tracked-bolt [] {:prepare true}
  [conf context collector]  
  (reset! bolt-prepared? true)
  (bolt
   (execute [tuple]
            (ack! collector tuple))))

(def spout-opened? (atom false))
(defspout open-tracked-spout ["val"]
  [conf context collector]
  (reset! spout-opened? true)
  (spout
   (nextTuple [])))

(deftest test-submit-inactive-topology
  (with-simulated-time-local-cluster [cluster :daemon-conf {TOPOLOGY-ENABLE-MESSAGE-TIMEOUTS true}]
    (let [feeder (feeder-spout ["field1"])
          tracker (AckFailMapTracker.)
          _ (.setAckFailDelegate feeder tracker)
          topology (thrift/mk-topology
                    {"1" (thrift/mk-spout-spec feeder)
                     "2" (thrift/mk-spout-spec open-tracked-spout)}
                    {"3" (thrift/mk-bolt-spec {"1" :global} prepare-tracked-bolt)})]
      (reset! bolt-prepared? false)
      (reset! spout-opened? false)      
      
      (submit-local-topology-with-opts (:nimbus cluster)
        "test"
        {TOPOLOGY-MESSAGE-TIMEOUT-SECS 10}
        topology
        (SubmitOptions. TopologyInitialStatus/INACTIVE))
      (.feedNoWait feeder ["a"] 1)
      (advance-cluster-time cluster 9)
      (is (not @bolt-prepared?))
      (is (not @spout-opened?))        
      (.activate (:nimbus cluster) "test")              
      
      (advance-cluster-time cluster 12)
      (assert-acked tracker 1)
      (is @bolt-prepared?)
      (is @spout-opened?))))

(deftest test-acking-self-anchor
  (with-tracked-cluster [cluster]
    (let [[feeder checker] (ack-tracking-feeder ["num"])
          tracked (mk-tracked-topology
                   cluster
                   (topology
                     {"1" (spout-spec feeder)}
                     {"2" (bolt-spec {"1" :shuffle} dup-anchor)
                      "3" (bolt-spec {"2" :shuffle} ack-bolt)}))]
      (submit-local-topology (:nimbus cluster)
                             "test"
                             {}
                             (:topology tracked))
      (.feed feeder [1])
      (tracked-wait tracked 1)
      (checker 1)
      (.feed feeder [1])
      (.feed feeder [1])
      (.feed feeder [1])
      (tracked-wait tracked 3)
      (checker 3)
      )))

;; (defspout ConstantSpout ["val"] {:prepare false}
;;   [collector]
;;   (Time/sleep 100)
;;   (emit-spout! collector [1]))

;; (def errored (atom false))
;; (def restarted (atom false))

;; (defbolt local-error-checker {} [tuple collector]
;;   (when-not @errored
;;     (reset! errored true)
;;     (println "erroring")
;;     (throw (RuntimeException.)))
;;   (when-not @restarted (println "restarted"))
;;   (reset! restarted true))

;; (deftest test-no-halt-local-mode
;;   (with-simulated-time-local-cluster [cluster]
;;       (let [topology (topology
;;                       {1 (spout-spec ConstantSpout)}
;;                       {2 (bolt-spec {1 :shuffle} local-error-checker)
;;                        })]
;;         (submit-local-topology (:nimbus cluster)
;;                                "test"
;;                                {}
;;                                topology)
;;         (while (not @restarted)
;;           (advance-time-ms! 100))
;;         )))

(defspout IncSpout ["word"]
  [conf context collector]
  (let [state (atom 0)]
    (spout
     (nextTuple []
       (Thread/sleep 100)
       (emit-spout! collector [@state] :id 1)         
       )
     (ack [id]
       (swap! state inc))
     )))


(defspout IncSpout2 ["word"] {:params [prefix]}
  [conf context collector]
  (let [state (atom 0)]
    (spout
     (nextTuple []
       (Thread/sleep 100)
       (swap! state inc)
       (emit-spout! collector [(str prefix "-" @state)])         
       )
     )))

;; (deftest test-clojure-spout
;;   (with-local-cluster [cluster]
;;     (let [nimbus (:nimbus cluster)
;;           top (topology
;;                {1 (spout-spec IncSpout)}
;;                {}
;;                )]
;;       (submit-local-topology nimbus
;;                              "spout-test"
;;                              {TOPOLOGY-DEBUG true
;;                               TOPOLOGY-MESSAGE-TIMEOUT-SECS 3}
;;                              top)
;;       (Thread/sleep 10000)
;;       (.killTopology nimbus "spout-test")
;;       (Thread/sleep 10000)
;;       )))

(deftest test-kryo-decorators-config
  (with-simulated-time-local-cluster [cluster
                                      :daemon-conf {TOPOLOGY-SKIP-MISSING-KRYO-REGISTRATIONS true
                                                    TOPOLOGY-KRYO-DECORATORS ["this-is-overriden"]}]
    (letlocals
     (bind builder (TopologyBuilder.))
     (.setSpout builder "1" (TestPlannerSpout. (Fields. ["conf"])))
     (-> builder
         (.setBolt "2"
                   (TestConfBolt.
                    {TOPOLOGY-KRYO-DECORATORS ["one" "two"]}))
         (.shuffleGrouping "1"))
     
     (bind results
           (complete-topology cluster
                              (.createTopology builder)
                              :storm-conf {TOPOLOGY-KRYO-DECORATORS ["one" "three"]}
                              :mock-sources {"1" [[TOPOLOGY-KRYO-DECORATORS]]}))
     (is (= {"topology.kryo.decorators" (list "one" "two" "three")}            
            (->> (read-tuples results "2")
                 (apply concat)
                 (apply hash-map)))))))

(deftest test-component-specific-config
  (with-simulated-time-local-cluster [cluster
                                      :daemon-conf {TOPOLOGY-SKIP-MISSING-KRYO-REGISTRATIONS true}]
    (letlocals
     (bind builder (TopologyBuilder.))
     (.setSpout builder "1" (TestPlannerSpout. (Fields. ["conf"])))
     (-> builder
         (.setBolt "2"
                   (TestConfBolt.
                    {"fake.config" 123
                     TOPOLOGY-MAX-TASK-PARALLELISM 20
                     TOPOLOGY-MAX-SPOUT-PENDING 30
                     TOPOLOGY-KRYO-REGISTER [{"fake.type" "bad.serializer"}
                                             {"fake.type2" "a.serializer"}]
                     }))
         (.shuffleGrouping "1")
         (.setMaxTaskParallelism (int 2))
         (.addConfiguration "fake.config2" 987)
         )
     

     (bind results
           (complete-topology cluster
                              (.createTopology builder)
                              :storm-conf {TOPOLOGY-KRYO-REGISTER [{"fake.type" "good.serializer" "fake.type3" "a.serializer3"}]}
                              :mock-sources {"1" [["fake.config"]
                                                  [TOPOLOGY-MAX-TASK-PARALLELISM]
                                                  [TOPOLOGY-MAX-SPOUT-PENDING]
                                                  ["fake.config2"]
                                                  [TOPOLOGY-KRYO-REGISTER]
                                                  ]}))
     (is (= {"fake.config" 123
             "fake.config2" 987
             TOPOLOGY-MAX-TASK-PARALLELISM 2
             TOPOLOGY-MAX-SPOUT-PENDING 30
             TOPOLOGY-KRYO-REGISTER {"fake.type" "good.serializer"
                                     "fake.type2" "a.serializer"
                                     "fake.type3" "a.serializer3"}}
            (->> (read-tuples results "2")
                 (apply concat)
                 (apply hash-map))
            ))
     )))

(defbolt hooks-bolt ["emit" "ack" "fail" "executed"] {:prepare true}
  [conf context collector]
  (let [acked (atom 0)
        failed (atom 0)
        executed (atom 0)
        emitted (atom 0)]
    (.addTaskHook context
                  (reify org.apache.storm.hooks.ITaskHook
                    (prepare [this conf context]
                      )
                    (cleanup [this]
                      )
                    (emit [this info]
                      (swap! emitted inc))
                    (boltAck [this info]
                      (swap! acked inc))
                    (boltFail [this info]
                      (swap! failed inc))
                    (boltExecute [this info]
                      (swap! executed inc))
                      ))
    (bolt
     (execute [tuple]
        (emit-bolt! collector [@emitted @acked @failed @executed])
        (if (= 0 (- @acked @failed))
          (ack! collector tuple)
          (fail! collector tuple))
        ))))

(deftest test-hooks
  (with-simulated-time-local-cluster [cluster]
    (let [topology (topology {"1" (spout-spec (TestPlannerSpout. (Fields. ["conf"])))
                              }
                             {"2" (bolt-spec {"1" :shuffle}
                                             hooks-bolt)
                              })
          results (complete-topology cluster
                                     topology
                                     :mock-sources {"1" [[1]
                                                         [1]
                                                         [1]
                                                         [1]
                                                         ]})]
      (is (= [[0 0 0 0]
              [2 1 0 1]
              [4 1 1 2]
              [6 2 1 3]]
             (read-tuples results "2")
             )))))

;;This is more of a unit test, but getting the timing right for
;; an integration test is really hard
(deftest test-throttled-errors
  (with-simulated-time
    (let [error-count (atom 0)
          worker-context (Mockito/mock WorkerTopologyContext)
          cluster-state
             (reify cluster/StormClusterState
               (report-error
                 [this storm-id component-id node port error]
                 (swap! error-count inc)))
          error-fn (executor/throttled-report-error-fn 
            {:storm-conf {TOPOLOGY-ERROR-THROTTLE-INTERVAL-SECS 10
                          TOPOLOGY-MAX-ERROR-REPORT-PER-INTERVAL 4}
             :storm-cluster-state cluster-state
             :storm-id "topo"
             :component-id "comp"
             :worker-context worker-context})]
        (. (Mockito/when (.getThisWorkerPort worker-context)) (thenReturn (Integer. 8080))) 
        (error-fn (RuntimeException. "ERROR-1"))
        (is (= 1 @error-count))
        (error-fn (RuntimeException. "ERROR-2"))
        (is (= 2 @error-count))
        (error-fn (RuntimeException. "ERROR-3"))
        (is (= 3 @error-count))
        (error-fn (RuntimeException. "ERROR-4"))
        (is (= 4 @error-count))
        ;;Ignored
        (error-fn (RuntimeException. "ERROR-5"))
        (is (= 4 @error-count))
        (Time/advanceTime 9000)
        (error-fn (RuntimeException. "ERROR-6"))
        (is (= 4 @error-count))
        (Time/advanceTime 2000)
        (error-fn (RuntimeException. "ERROR-7"))
        (is (= 5 @error-count)))))

(deftest test-acking-branching-complex
  ;; test acking with branching in the topology
  )


(deftest test-fields-grouping
  ;; 1. put a shitload of random tuples through it and test that counts are right
  ;; 2. test that different spouts with different phints group the same way
  )

(deftest test-all-grouping
  )

(deftest test-direct-grouping
  )
