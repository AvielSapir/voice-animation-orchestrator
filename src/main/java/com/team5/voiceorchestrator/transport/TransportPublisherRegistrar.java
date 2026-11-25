package com.team5.voiceorchestrator.transport;

import com.team5.voiceorchestrator.contracts.TherapistTranscriptEvent;
import com.team5.voiceorchestrator.contracts.UnityResponsePacket;
import com.team5.voiceorchestrator.orchestration.TherapistEventPublisher;
import com.team5.voiceorchestrator.orchestration.UnityResponsePublisher;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.stereotype.Component;

/**
 * Registers default transport-backed {@link TherapistEventPublisher} and
 * {@link UnityResponsePublisher} beans only when no explicit/test publisher bean
 * is already defined in the application context.
 */
@Component
public class TransportPublisherRegistrar implements BeanDefinitionRegistryPostProcessor {

    private static final String BEAN_THERAPIST_PUBLISHER = "transportTherapistEventPublisher";
    private static final String BEAN_UNITY_PUBLISHER = "transportUnityResponsePublisher";

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (!(registry instanceof ListableBeanFactory lbf)) {
            return;
        }

        if (lbf.getBeanNamesForType(TherapistEventPublisher.class, true, false).length == 0
                && !registry.containsBeanDefinition(BEAN_THERAPIST_PUBLISHER)) {
            GenericBeanDefinition def = new GenericBeanDefinition();
            def.setBeanClass(TransportTherapistEventPublisher.class);
            registry.registerBeanDefinition(BEAN_THERAPIST_PUBLISHER, def);
        }

        if (lbf.getBeanNamesForType(UnityResponsePublisher.class, true, false).length == 0
                && !registry.containsBeanDefinition(BEAN_UNITY_PUBLISHER)) {
            GenericBeanDefinition def = new GenericBeanDefinition();
            def.setBeanClass(TransportUnityResponsePublisher.class);
            registry.registerBeanDefinition(BEAN_UNITY_PUBLISHER, def);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // No-op
    }

    public static final class TransportTherapistEventPublisher implements TherapistEventPublisher {

        private final TherapistWebSocketHandler therapistWebSocketHandler;

        public TransportTherapistEventPublisher(TherapistWebSocketHandler therapistWebSocketHandler) {
            this.therapistWebSocketHandler = therapistWebSocketHandler;
        }

        @Override
        public void publish(TherapistTranscriptEvent event) {
            if (event != null && event.sessionId() != null) {
                therapistWebSocketHandler.sendTranscriptEvent(event.sessionId(), event);
            }
        }
    }

    public static final class TransportUnityResponsePublisher implements UnityResponsePublisher {

        private final UnityWebSocketHandler unityWebSocketHandler;

        public TransportUnityResponsePublisher(UnityWebSocketHandler unityWebSocketHandler) {
            this.unityWebSocketHandler = unityWebSocketHandler;
        }

        @Override
        public void publish(UnityResponsePacket packet) {
            if (packet != null) {
                unityWebSocketHandler.publishResponsePacket(packet);
            }
        }
    }
}
