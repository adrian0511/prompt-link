package io.github.adrian0511.prompt_link.dto;

/**
 * Un mensaje de la conversación. El {@code role} le dice al modelo quién habla: {@code system} para
 * las instrucciones de comportamiento, {@code user} para lo que escribe la persona y
 * {@code assistant} para lo que respondió el modelo en turnos anteriores.
 *
 * <p>Usa los métodos de fábrica ({@link #system}, {@link #user}, {@link #assistant}) en lugar del
 * constructor para no equivocarte con el nombre del rol, que la API valida.
 */
public class Message {

    private String role;
    private String content;

    public Message() {
    }

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /** Instrucciones de comportamiento para el modelo. */
    public static Message system(String content) {
        return new Message("system", content);
    }

    /** Un mensaje escrito por la persona. */
    public static Message user(String content) {
        return new Message("user", content);
    }

    /** Una respuesta previa del modelo, para reconstruir el historial de la conversación. */
    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

}
