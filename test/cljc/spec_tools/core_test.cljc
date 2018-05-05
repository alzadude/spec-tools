(ns spec-tools.core-test
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [spec-tools.core :as st]
            [spec-tools.spec :as spec]
            [spec-tools.parse :as info]
            [spec-tools.form :as form]
            [#?(:clj  clojure.spec.gen.alpha
                :cljs cljs.spec.gen.alpha) :as gen]
            [spec-tools.transformer :as stt]))

(s/def ::age (s/and spec/integer? #(> % 10)))
(s/def ::over-a-million (s/and spec/int? #(> % 1000000)))
(s/def ::lat spec/double?)
(s/def ::language (s/and spec/keyword? #{:clojure :clojurescript}))
(s/def ::truth spec/boolean?)
(s/def ::uuid spec/uuid?)
(s/def ::birthdate spec/inst?)

(s/def ::a spec/int?)
(s/def ::b ::a)

(s/def ::string string?)
(s/def ::alias ::string)

(deftest get-spec-test
  (is (= spec/int? (st/get-spec ::a)))
  (is (= spec/int? (st/get-spec ::b))))

(deftest coerce-test
  (is (= spec/boolean? (st/coerce-spec ::truth)))
  (is (= spec/boolean? (st/coerce-spec spec/boolean?)))
  (is (thrown? #?(:clj Exception, :cljs js/Error) (st/coerce-spec ::INVALID))))

(s/def ::regex (s/or :int int? :string string?))
(s/def ::spec (s/spec int?))

(deftest spec-name-test
  (is (= nil (st/spec-name #{1 2})))
  (is (= :kikka (st/spec-name :kikka)))
  (is (= ::regex (st/spec-name (s/get-spec ::regex))))
  (is (= ::spec (st/spec-name (s/get-spec ::spec))))
  (is (= ::overridden (st/spec-name
                        (st/spec
                          {:spec (s/get-spec ::spec)
                           :name ::overridden})))))

(deftest spec-description-test
  (is (= nil (st/spec-description #{1 2})))
  (is (= "description" (st/spec-description
                         (st/spec
                           {:spec (s/get-spec ::spec)
                            :description "description"})))))

(deftest spec?-test
  (testing "spec"
    (let [spec (s/spec integer?)]
      (is (= spec (s/spec? spec)))
      (is (nil? (st/spec? spec)))))
  (testing "Spec"
    (let [spec (st/spec integer?)]
      (is (= spec (s/spec? spec)))
      (is (= spec (st/spec? spec))))))

(deftest spec-test
  (let [my-integer? (st/spec integer?)]

    (testing "creation"
      (testing "succeeds"
        (is (= spec/integer?
               (st/spec integer?)
               (st/spec {:spec integer?})
               (st/spec {:spec integer?, :type :long})
               (st/spec integer? {:type :long})
               (st/create-spec {:spec integer?})
               (st/create-spec {:spec integer? :type :long})
               (st/create-spec {:spec integer?, :form `integer?})
               (st/create-spec {:spec integer?, :form `integer?, :type :long}))))
      (testing "fails"
        (is (thrown? #?(:clj AssertionError, :cljs js/Error)
                     (st/create-spec {:spec :un-existent/keyword-spec}))))

      (testing "::s/name is retained"
        (is (= ::age (::s/name (meta (st/create-spec {:spec ::age}))))))

      (testing "anonymous functions"

        (testing ":form default to ::s/unknown"
          (let [spec (st/create-spec
                       {:name "positive?"
                        :spec (fn [x] (pos? x))})]
            (is (st/spec? spec))
            (is (= (:form spec) ::s/unknown))))

        (testing ":form and :type can be provided"
          (let [spec (st/create-spec
                       {:name "positive?"
                        :spec (fn [x] (pos? x))
                        :type :long
                        :form `(fn [x] (pos? x))})]
            (is (st/spec? spec))))))

    (testing "registered specs are inlined"
      (is (= (s/get-spec ::string)
             (:spec (st/spec ::string))))
      (is (= (s/form ::string)
             (:form (st/spec ::string)))))

    (testing "nested specs are inlined"
      (is (= (s/get-spec ::string)
             (:spec (st/spec ::alias))))
      (is (= (s/form ::string)
             (:form (st/spec ::alias)))))

    (testing "forms"
      (are [spec form]
        (= form (s/form spec))

        (st/spec integer?)
        `(spec-tools.core/spec
           {:spec integer?
            :type :long})

        (st/spec #{pos? neg?})
        `(spec-tools.core/spec
           {:spec #{neg? pos?}
            :type nil})

        (st/spec ::string)
        `(spec-tools.core/spec
           {:spec string?
            :type :string})

        (st/spec ::lat)
        `(spec-tools.core/spec
           {:spec (spec-tools.core/spec
                    {:spec double?
                     :type :double})
            :type :double})

        (st/spec (fn [x] (> x 10)))
        `(spec-tools.core/spec
           {:spec (clojure.core/fn [~'x] (> ~'x 10))
            :type nil})

        (st/spec #(> % 10))
        `(spec-tools.core/spec
           {:spec (clojure.core/fn [~'%] (> ~'% 10))
            :type nil})))

    (testing "wrapped predicate work as a predicate"
      (is (true? (my-integer? 1)))
      (is (false? (my-integer? "1")))
      (testing "ifn's work too"
        (let [spec (st/spec #{1 2 3})]
          (is (= 1 (spec 1)))
          (is (= nil (spec "1"))))))

    (testing "wrapped spec does not work as a predicate"
      (let [my-spec (st/spec (s/keys :req [::age]))]
        (is (thrown? #?(:clj Exception, :cljs js/Error) (my-spec {::age 20})))))

    (testing "adding info"
      (let [info {:description "desc"
                  :example 123}]
        (is (= info (:info (assoc my-integer? :info info))))))

    (testing "are specs"
      (is (true? (s/valid? my-integer? 1)))
      (is (false? (s/valid? my-integer? "1")))

      (testing "fully qualifed predicate symbol is returned with s/form"
        (is (= ['spec-tools.core/spec
                {:spec #?(:clj  'clojure.core/integer?
                          :cljs 'cljs.core/integer?)
                 :type :long}] (s/form my-integer?)))
        (is (= ['spec {:spec 'integer? :type :long}] (s/describe my-integer?))))

      (testing "type resolution"
        (is (= (st/spec integer?)
               (st/spec integer? {:type :long}))))

      (testing "serialization"
        (let [spec (st/spec {:spec integer? :description "cool", :type ::integer})]
          (is (= `(st/spec {:spec integer? :description "cool", :type ::integer})
                 (s/form spec)
                 (st/deserialize (st/serialize spec))))))

      (testing "gen"
        (is (seq? (s/exercise my-integer?)))
        (is (every? #{:kikka :kukka} (-> spec/keyword?
                                         (s/with-gen #(s/gen #{:kikka :kukka}))
                                         (s/exercise)
                                         (->> (map first)))))))))

(deftest doc-test

  (testing "creation"
    (is (= (st/doc integer? {:description "kikka"})
           (st/doc {:spec integer?, :description "kikka"})
           (st/doc integer? {:description "kikka"})
           (st/spec {:spec integer?, :description "kikka", :type nil}))))

  (testing "just docs, #12"
    (let [spec (st/doc integer? {:description "kikka"})]
      (is (= "kikka" (:description spec)))
      (is (true? (s/valid? spec 1)))
      (is (false? (s/valid? spec "1")))
      (is (= `(st/spec {:spec integer? :description "kikka", :type nil})
             (st/deserialize (st/serialize spec))
             (s/form spec))))))

(deftest reason-test
  (let [expected-problem {:path [] :pred `pos-int?, :val -1, :via [], :in []}]
    (testing "explain-data"
      (let [spec (st/spec pos-int?)]
        (is (= #?(:clj  #:clojure.spec.alpha{:problems [expected-problem]
                                             :spec spec
                                             :value -1}
                  :cljs #:cljs.spec.alpha{:problems [expected-problem]
                                          :spec spec
                                          :value -1})
               (st/explain-data spec -1)
               (s/explain-data spec -1)))))
    (testing "explain-data with reason"
      (let [spec (st/spec pos-int? {:reason "positive"})]
        (is (= #?(:clj  #:clojure.spec.alpha{:problems [(assoc expected-problem :reason "positive")]
                                             :spec spec
                                             :value -1}
                  :cljs #:cljs.spec.alpha{:problems [(assoc expected-problem :reason "positive")]
                                          :spec spec
                                          :value -1})
               (st/explain-data spec -1)
               (s/explain-data spec -1)))))))

(deftest spec-tools-transform-test
  (testing "in default mode"
    (testing "nothing is conformed"
      (is (= st/+invalid+ (st/conform ::age "12")))
      (is (= st/+invalid+ (st/conform ::over-a-million "1234567")))
      (is (= st/+invalid+ (st/conform ::lat "23.1234")))
      (is (= st/+invalid+ (st/conform ::language "clojure")))
      (is (= st/+invalid+ (st/conform ::truth "false")))
      (is (= st/+invalid+ (st/conform ::uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e")))
      (is (= st/+invalid+ (st/conform ::birthdate "2014-02-18T18:25:37.456Z")))
      (is (= st/+invalid+ (st/conform ::birthdate "2014-02-18T18:25:37Z")))))

  (testing "string-transformer"
    (let [conform #(st/conform %1 %2 st/string-transformer)]
      (testing "everything gets conformed"
        (is (= 12 (conform ::age "12")))
        (is (= 1234567 (conform ::over-a-million "1234567")))
        (is (= 23.1234 (conform ::lat "23.1234")))
        (is (= false (conform ::truth "false")))
        (is (= :clojure (conform ::language "clojure")))
        (is (= #uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e"
               (conform ::uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e")))
        (is (= #inst "2014-02-18T18:25:37.456Z"
               (conform ::birthdate "2014-02-18T18:25:37.456Z")))
        (is (= #inst "2014-02-18T18:25:37Z"
               (conform ::birthdate "2014-02-18T18:25:37Z"))))))

  (testing "json-transformer"
    (let [conform #(st/conform %1 %2 st/json-transformer)]
      (testing "some are not conformed"
        (is (= st/+invalid+ (conform ::age "12")))
        (is (= st/+invalid+ (conform ::over-a-million "1234567")))
        (is (= st/+invalid+ (conform ::lat "23.1234")))
        (is (= st/+invalid+ (conform ::truth "false"))))
      (testing "some are conformed"
        (is (= :clojure (conform ::language "clojure")))
        (is (= #uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e"
               (conform ::uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e")))
        (is (= #inst "2014-02-18T18:25:37.456Z"
               (conform ::birthdate "2014-02-18T18:25:37.456Z")))
        (is (= #inst "2014-02-18T18:25:37Z"
               (conform ::birthdate "2014-02-18T18:25:37Z")))))))

(s/def ::my-spec
  (st/spec
    {:spec #(and (simple-keyword? %) (-> % name str/lower-case keyword (= %)))
     :description "a lowercase simple keyword, encoded in uppercase in string-mode"
     :decode/string #(-> %2 name str/lower-case keyword)
     :encode/string #(-> %2 name str/upper-case)}))
(s/def ::my-spec-map (s/keys :req [::my-spec]))

(deftest encode-decode-test
  (testing "spec-driven encode & decode"
    (let [invalid {::my-spec "kikka"}
          encoded {::my-spec "KIKKA"}
          decoded {::my-spec :kikka}]
      (testing "without transformer"
        (testing "decode works just like s/conform"
          (is (= ::s/invalid (st/decode ::my-spec-map encoded)))
          (is (= decoded (st/decode ::my-spec-map decoded))))
        (testing "encode fails if no encoder is defined"
          (is (= ::s/invalid (st/encode ::my-spec-map invalid)))))
      (testing "with transformer"
        (testing "decoding is applied before validation, if defined"
          (is (= ::s/invalid (st/decode ::my-spec-map encoded st/json-transformer)))
          (is (= decoded (st/decode ::my-spec-map decoded st/string-transformer)))
          (is (= decoded (st/decode ::my-spec-map encoded st/string-transformer))))
        (testing "encoding is applied without validation, if defined"
          (is (= ::s/invalid (st/encode ::my-spec-map decoded st/json-transformer)))
          (is (= encoded (st/encode ::my-spec-map encoded st/string-transformer)))
          (is (= encoded (st/encode ::my-spec-map decoded st/string-transformer))))))))

(deftest conform!-test
  (testing "suceess"
    (is (= 12 (st/conform! ::age "12" st/string-transformer))))
  (testing "failing"
    (is (thrown? #?(:clj Exception, :cljs js/Error) (st/conform! ::age "12")))
    (try
      (st/conform! ::age "12")
      (catch #?(:clj Exception, :cljs js/Error) e
        (let [data (ex-data e)]
          (is (= {:type ::st/conform
                  :problems [{:path [], :pred `integer?, :val "12", :via [::age], :in []}]
                  :spec :spec-tools.core-test/age
                  :value "12"}
                 data)))))))

(deftest explain-tests
  (testing "without transformer"
    (let [expected-problem {:path [], :pred `int?, :val "12", :via [], :in []}]
      (is (= st/+invalid+ (st/conform spec/int? "12")))
      (is (= #?(:clj  #:clojure.spec.alpha{:problems [expected-problem]
                                           :spec spec/int?
                                           :value "12"}
                :cljs #:cljs.spec.alpha{:problems [expected-problem]
                                        :spec spec/int?
                                        :value "12"})
             (st/explain-data spec/int? "12")))
      (is (any? (with-out-str (st/explain spec/int? "12"))))
      (is (any? (with-out-str (st/explain spec/int? "12" nil))))))
  (testing "with transformer"
    (is (= 12 (st/conform spec/int? "12" st/string-transformer)))
    (is (= nil (st/explain-data spec/int? "12" st/string-transformer)))
    (is (= "Success!\n"
           (with-out-str (st/explain spec/int? "12" st/string-transformer))))))

(deftest conform-unform-explain-tests
  (testing "specs"
    (let [spec (st/spec (s/or :int spec/int? :bool spec/boolean?))
          value "1"]
      (is (= st/+invalid+ (st/conform spec value)))
      (is (= [:int 1] (st/conform spec value st/string-transformer)))
      (is (= 1 (s/unform spec (st/conform spec value st/string-transformer))))
      (is (= nil (st/explain-data spec value st/string-transformer)))))
  (testing "regexs"
    (let [spec (st/spec (s/* (s/cat :key spec/keyword? :val spec/int?)))
          value [:a "1" :b "2"]]
      (is (= st/+invalid+ (st/conform spec value)))
      (is (= [{:key :a, :val 1} {:key :b, :val 2}] (st/conform spec value st/string-transformer)))
      (is (= [:a 1 :b 2] (s/unform spec (st/conform spec value st/string-transformer))))
      (is (= nil (st/explain-data spec value st/string-transformer))))))

(s/def ::height integer?)
(s/def ::weight integer?)
(s/def ::person (st/spec (s/keys :req-un [::height ::weight])))

(deftest map-specs-test
  (let [person {:height 200, :weight 80, :age 36}]

    (testing "conform"
      (is (= {:height 200, :weight 80, :age 36}
             (s/conform ::person person)
             (st/conform ::person person))))

    (testing "stripping extra keys"
      (is (= {:height 200, :weight 80}
             (st/conform ::person person st/strip-extra-keys-transformer)
             (st/select-spec ::person person))))

    (testing "failing on extra keys"
      (is (= st/+invalid+
             (st/conform ::person person st/fail-on-extra-keys-transformer))))

    (testing "explain works too"
      (is (is (seq (st/explain-data ::person person st/fail-on-extra-keys-transformer)))))))

(s/def ::human (st/spec (s/keys :req-un [::height ::weight]) {:type ::human}))

(defn bmi [{:keys [height weight]}]
  (let [h (/ height 100)]
    (double (/ weight (* h h)))))

(deftest custom-map-specs-test
  (let [person {:height 200, :weight 80}
        bmi-conformer (fn [_ human]
                        (assoc human :bmi (bmi human)))]

    (testing "conform"
      (is (= {:height 200, :weight 80}
             (s/conform ::human person)
             (st/conform ::human person))))

    (testing "bmi-transformer"
      (is (= {:height 200, :weight 80, :bmi 20.0}
             (st/conform ::human person (st/type-transformer
                                          {:decoders {::human bmi-conformer}})))))))

(deftest unform-test
  (let [unform-conform #(s/unform %1 (st/conform %1 %2 st/string-transformer))]
    (testing "conformed values can be unformed"
      (is (= 12 (unform-conform ::age "12")))
      (is (= 1234567 (unform-conform ::age "1234567")))
      (is (= 23.1234 (unform-conform ::lat "23.1234")))
      (is (= false (unform-conform ::truth "false")))
      (is (= :clojure (unform-conform ::language "clojure")))
      (is (= #uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e"
             (unform-conform ::uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e")))
      (is (= #inst "2014-02-18T18:25:37.456Z"
             (unform-conform ::birthdate "2014-02-18T18:25:37.456Z")))
      (is (= #inst "2014-02-18T18:25:37.456Z"
             (unform-conform ::birthdate "2014-02-18T18:25:37.456Z"))))))

(deftest extending-test
  (let [my-transformer (st/type-transformer
                         {:decoders
                          (assoc
                            stt/string-type-decoders
                            :keyword
                            (fn [_ value]
                              (-> value
                                  str/upper-case
                                  str/reverse
                                  keyword)))})]
    (testing "string-transformer"
      (is (= :kikka (st/conform spec/keyword? "kikka" st/string-transformer))))
    (testing "my-transformer"
      (is (= :AKKIK (st/conform spec/keyword? "kikka" my-transformer))))))

(s/def ::collect-info-spec (s/keys
                             :req [::age]
                             :req-un [::lat]
                             :opt [::truth]
                             :opt-un [::uuid]))

(deftest collect-info-test
  (testing "doesn't fail with ::s/unknown"
    (is (= nil
           (info/parse-spec
             ::s/unknown))))

  (testing "all keys types are extracted"
    (is (= {:type :map
            :keys #{::age :lat ::truth :uuid}
            :keys/req #{::age :lat}
            :keys/opt #{::truth :uuid}}

           ;; named spec
           (info/parse-spec
             ::collect-info-spec)

           ;; spec
           (info/parse-spec
             (s/keys
               :req [::age]
               :req-un [::lat]
               :opt [::truth]
               :opt-un [::uuid]))

           ;; form
           (info/parse-spec
             (s/form
               (s/keys
                 :req [::age]
                 :req-un [::lat]
                 :opt [::truth]
                 :opt-un [::uuid]))))))

  (testing "ands and ors are flattened"
    (is (= {:type :map
            :keys #{::age ::lat ::uuid}
            :keys/req #{::age ::lat ::uuid}}
           (info/parse-spec
             (s/keys
               :req [(or ::age (and ::uuid ::lat))]))))))

(deftest type-inference-test
  (testing "works for core predicates"
    (is (= :long (:type (info/parse-spec `integer?)))))
  (testing "works for conjunctive predicates"
    (is (= :long (:type (info/parse-spec `(s/and integer? #(> % 42)))))))
  (testing "unknowns return nil"
    (is (= nil (:type (info/parse-spec #(> % 2))))))
  (testing "available types"
    (is (not (empty? (info/types))))
    (is (contains? (info/types) :boolean)))
  (testing "available type-symbols"
    (is (not (empty? (info/type-symbols))))
    (is (contains? (info/type-symbols) 'clojure.spec.alpha/keys))
    (is (contains? (info/type-symbols) 'clojure.core/integer?))))

(deftest form-inference-test
  (testing "works for core predicates"
    (is (= `integer? (form/resolve-form integer?))))
  (testing "lists return identity"
    (is (= `(s/coll-of integer?) (form/resolve-form `(s/coll-of integer?)))))
  (testing "qualified keywords return identity"
    (is (= ::kikka (form/resolve-form ::kikka))))
  (testing "unqualified keywords return unknown"
    (is (= ::s/unknown (form/resolve-form :kikka))))
  (testing "unknowns return unknown"
    (is (= ::s/unknown (form/resolve-form #(> % 2))))))

(s/def ::kw1 spec/keyword?)
(s/def ::m1 (s/keys :req-un [::kw1]))
(s/def ::kw2 spec/keyword?)
(s/def ::map (st/merge ::m1 (s/keys :opt-un [::kw2])))
(s/def ::core-map (s/merge ::m1 (s/keys :opt-un [::kw2])))

(s/def ::or (s/or :int int? :string string?))
(s/def ::or-map (st/merge (s/keys :req-un [::or])
                          (s/keys :opt-un [::kw2])))

(deftest merge-test
  (let [input {:kw1 "kw1"
               :kw2 "kw2"}
        bad-input {:kw2 :kw2}
        output {:kw1 :kw1
                :kw2 :kw2}]
    (testing "clojure.spec.alpha/merge"
      (testing "fails to conform all values with spec-tools.core/conform"
        (is (= {:kw1 "kw1"
                :kw2 :kw2}
               (st/conform ::core-map input st/json-transformer)))))
    (testing "spec-tools.core/merge"
      (testing "creates a conformer that conforms maps inside merge with spec-tools.core/conform"
        (is (= output (st/conform ::map input st/json-transformer)))
        (testing "also for non-spectools specs"
          (is (= {:or [:int 1]} (st/conform ::or-map {:or 1})))
          (is (= {:or [:string "1"]} (st/conform ::or-map {:or "1"}))))
        (testing "also for nested spec-tools.core/merge"
          (is (= output (st/conform (st/merge ::map) input st/json-transformer)))))
      (testing "fails with bad input"
        (is (not (s/valid? ::map bad-input))))
      (testing "doesn't strip extra keys from input"
        (is (= (assoc output :foo true)
               (st/conform ::map (assoc input :foo true) st/json-transformer))))
      (testing "works with strip-extra-keys-transformer"
        (is (= output
               (st/conform ::map (assoc output :foo true) st/strip-extra-keys-transformer))))
      (testing "has proper unform"
        (is (= output (s/conform ::map (s/unform ::map (st/conform ::map input st/json-transformer)))))
        (testing "also for non-spectools specs"
          (is (= {:or 1} (s/unform ::or-map (st/conform ::or-map {:or 1} st/json-transformer))))
          (is (= {:or "1"} (s/unform ::or-map (st/conform ::or-map {:or "1"} st/json-transformer))))))
      (testing "has a working generator"
        (is (s/valid? ::map (gen/generate (s/gen ::map)))))
      (testing "has a working with-gen"
        (let [new-spec (s/with-gen ::map #(gen/return output))]
          (testing "that creates a conformer that conforms maps inside merge"
            (is (= output (st/conform new-spec input st/json-transformer))))
          (testing "that uses the given generator"
            (is (= output (gen/generate (s/gen new-spec)))))))
      (testing "has the same explain as clojure.spec.alpha/merge"
        (let [expected-explanation (s/explain-data ::core-map bad-input)
              actual-explanation (s/explain-data ::map bad-input)]
          (is (= (select-keys (first (::s/problems expected-explanation))
                              [:path :pred :val :in])
                 (select-keys (first (::s/problems actual-explanation))
                              [:path :pred :val :in])))))
      (testing "has a working describe"
        (is (= (s/describe ::core-map)
               (:spec (second (s/describe ::map)))))))))
