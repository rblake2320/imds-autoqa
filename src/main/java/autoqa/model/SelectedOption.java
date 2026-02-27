package autoqa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents the option chosen in a {@code <select>} dropdown.
 * At least one of {@code text}, {@code value}, or {@code index} is populated.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SelectedOption {

    @JsonProperty("text")
    private String text;

    @JsonProperty("value")
    private String value;

    @JsonProperty("index")
    private Integer index;

    public SelectedOption() {}

    public SelectedOption(String text, String value, Integer index) {
        this.text  = text;
        this.value = value;
        this.index = index;
    }

    public String  getText()  { return text; }
    public String  getValue() { return value; }
    public Integer getIndex() { return index; }

    public void setText(String text)    { this.text = text; }
    public void setValue(String value)  { this.value = value; }
    public void setIndex(Integer index) { this.index = index; }

    @Override
    public String toString() {
        return String.format("SelectedOption{text='%s', value='%s', index=%d}", text, value, index);
    }
}
