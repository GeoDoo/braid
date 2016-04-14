(ns braid.ui.views.thread
  (:require [clojure.string :as string]
            [reagent.core :as r]
            [chat.client.reagent-adapter :refer [reagent->react subscribe]]
            [chat.client.store :as store]
            [chat.client.dispatcher :refer [dispatch!]]
            [chat.client.s3 :as s3]
            [braid.ui.views.pills :refer [user-pill-view tag-pill-view]]
            [braid.ui.views.message :refer [message-view]]
            [braid.ui.views.new-message :refer [new-message-view]])
  (:import [goog.events KeyCodes]))

(def max-file-size (* 10 1024 1024))

(defn thread-tags-view [thread]
  (let [tags (subscribe [:tags-for-thread (thread :id)])
        mentions (subscribe [:mentions-for-thread (thread :id)])]
    [:div.tags
     (doall
       (for [user @mentions]
         ^{:key (user :id)}
         [user-pill-view user]))
     (doall
       (for [tag @tags]
         ^{:key (tag :id)}
         [tag-pill-view tag]))]))

(defn- unseen? [message thread]
  (> (:created-at message)
     (thread :last-open-at)))

(defn- thread-private? [thread]
  (and
    (not (thread :new?))
    (empty? (thread :tag-ids))
    (seq (thread :mentioned-ids))))

(defn- thread-limbo? [thread]
  (and
    (not (thread :new?))
    (empty? (thread :tag-ids))
    (empty? (thread :mentioned-ids))))

(defn messages-view [thread]
  (let [scroll-to-bottom!
        (fn [component]
          (when-let [messages (r/dom-node component)]
            (set! (.-scrollTop messages) (.-scrollHeight messages))))

        sorted-messages
        (->> (thread :messages)
             (sort-by :created-at)
             (cons nil)
             (partition 2 1)
             (map (fn [[prev-message message]]
                    (assoc message
                      :unseen?
                      (unseen? message thread)
                      :first-unseen?
                      (and
                        (unseen? message thread)
                        (not (unseen? prev-message thread)))
                      :collapse?
                      (and
                        (= (:user-id message)
                           (:user-id prev-message))
                        (> (* 2 60 1000) ; 2 minutes
                           (- (:created-at message)
                              (or (:created-at prev-message) 0))))))))]
    (r/create-class
      {:component-did-mount scroll-to-bottom!

       :component-will-receive-props scroll-to-bottom!

       :component-did-update scroll-to-bottom!

       :reagent-render
       (fn []
         [:div.messages
          (doall
            (for [message sorted-messages]
              ^{:key (message :id)}
              [message-view message]))])})))

(defn thread-view [thread]
  (let [state (r/atom {:dragging? false
                       :uploading? false
                       :focused? false})
        set-uploading! (fn [bool] (swap! state assoc :uploading? bool))
        set-focused! (fn [bool] (swap! state assoc :focused? bool))
        set-dragging! (fn [bool] (swap! state assoc :dragging bool))

        open? (subscribe [:thread-open? (thread :id)])

        maybe-upload-file!
        (fn [file]
          (if (> (.-size file) max-file-size)
            (store/display-error! :upload-fail "File to big to upload, sorry")
            (do (set-uploading! true)
                (s3/upload file (fn [url]
                                  (set-uploading! false)
                                  (dispatch! :new-message
                                             {:content url
                                              :thread-id (thread :id)}))))))]

    (fn [thread]
      (let [{:keys [dragging? uploading? focused?]} @state
            new? (thread :new?)
            private? (thread-private? thread)
            limbo? (thread-limbo? thread)]

        [:div.thread
         {:class
          (string/join " " [(when new? "new")
                            (when private? "private")
                            (when limbo? "limbo")
                            (when focused? "focused")
                            (when dragging? "dragging")])

          :on-focus
          (fn [e]
            (set-focused! true))

          :on-blur
          (fn [e]
            (set-focused! false)
            (dispatch! :mark-thread-read (thread :id)))

          :on-key-up
          (fn [e]
            (when (or (and
                        (= KeyCodes.X (.-keyCode e))
                        (.-ctrlKey e))
                      (= KeyCodes.ESC (.-keyCode e)))
              (.preventDefault e)
              (.stopPropagation e)
              (dispatch! :hide-thread {:thread-id (thread :id)})))

          :on-paste
          (fn [e]
            (let [pasted-files (.. e -clipboardData -files)]
              (when (< 0 (.-length pasted-files))
                (.preventDefault e)
                (maybe-upload-file! (aget pasted-files 0)))))

          :on-drag-over
          (fn [e]
            (.stopPropagation e)
            (.preventDefault e)
            (set-dragging! true))

          :on-drag-leave
          (fn [e]
            (set-dragging! false))

          :on-drop
          (fn [e]
            (.preventDefault e)
            (set-dragging! false)
            (let [file-list (.. e -dataTransfer -files)]
              (when (< 0 (.-length file-list))
                (maybe-upload-file! (aget file-list 0)))))}

         (when limbo?
           [:div.notice "No one can see this conversation yet. Mention a @user or #tag in a reply."])

         (when private?
           [:div.notice
            "This is a private conversation." [:br]
            "Only @mentioned users can see it."])

         [:div.card
          [:div.head
           (when open?
             [:div.close
              {:on-click (fn [_]
                           (dispatch! :hide-thread {:thread-id (thread :id)}))} "×"])
           [thread-tags-view thread]]

          (when-not new?
            [messages-view thread])

          [new-message-view {:thread-id (thread :id)
                             :placeholder (if new?
                                            "Start a conversation..."
                                            "Reply...")
                             :mentioned-user-ids (thread :mentioned-ids)
                             :mentioned-tag-ids (thread :tag-ids)}]]]))))

