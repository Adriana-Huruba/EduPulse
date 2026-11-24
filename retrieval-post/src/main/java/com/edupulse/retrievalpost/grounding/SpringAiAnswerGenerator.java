package com.edupulse.retrievalpost.grounding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spring AI {@link ChatModel}-backed {@link AnswerGenerator}.
 */
@Component
public class SpringAiAnswerGenerator implements AnswerGenerator {

	private static final Logger log = LoggerFactory.getLogger(SpringAiAnswerGenerator.class);

	private final ObjectProvider<ChatModel> chatModelProvider;

	public SpringAiAnswerGenerator(ObjectProvider<ChatModel> chatModelProvider) {
		this.chatModelProvider = chatModelProvider;
	}

	@Override
	public String generate(String systemPrompt, String userPrompt) {
		ChatModel chatModel = chatModelProvider.getIfAvailable();
		if (chatModel == null) {
			throw new GroundingException("No Spring AI ChatModel bean is configured for answer generation");
		}
		try {
			String response = ChatClient.create(chatModel)
					.prompt()
					.system(systemPrompt)
					.user(userPrompt)
					.call()
					.content();
			if (response == null || response.isBlank()) {
				throw new GroundingException("LLM returned an empty response");
			}
			return response.strip();
		}
		catch (GroundingException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			log.error("Spring AI chat generation failed: {}", ex.getMessage(), ex);
			throw new GroundingException("LLM answer generation failed: " + ex.getMessage(), ex);
		}
	}
}
