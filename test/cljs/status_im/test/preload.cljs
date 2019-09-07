(ns status-im.test.preload)

(.mock js/jest
       "react-native"
       (fn [] (clj->js {:StyleSheet {:create (.fn js/jest)}
                        :NativeModules {:RNGestureHandlerModule {:Direction (.fn js/jest)}
                                        :ReanimatedModule {:configureProps (.fn js/jest)}}
                        :requireNativeComponent (fn [] (clj->js {:propTypes ""}))
                        :Animated {:createAnimatedComponent (.fn js/jest)}
                        :Easing {:bezier (.fn js/jest)
                                 :poly (.fn js/jest)
                                 :out (.fn js/jest)
                                 :in (.fn js/jest)
                                 :inOut (.fn js/jest)}
                        :Dimensions {:get (fn [] (clj->js {:height "" :width ""}))}
                        :Platform {:select (.fn js/jest)}
                        :I18nManager {:isRTL ""}
                        :NativeEventEmitter (.fn js/jest)})))
