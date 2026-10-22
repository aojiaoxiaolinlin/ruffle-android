// Regenerate with asc.jar -AS3 -import playerglobal_import.abc
// -swf SystemKeyboardFixture,640,360 SystemKeyboardFixture.as.
package {
    import flash.display.Sprite;
    import flash.events.Event;
    import flash.text.TextField;
    import flash.text.TextFormat;

    public class SystemKeyboardFixture extends Sprite {
        public function SystemKeyboardFixture() {
            var field:TextField = new TextField();
            field.type = "input";
            field.border = true;
            field.background = true;
            field.backgroundColor = 0xFFFFFF;
            field.defaultTextFormat = new TextFormat("_sans", 24, 0x000000);
            field.x = 40;
            field.y = 30;
            field.width = 560;
            field.height = 60;
            field.addEventListener(Event.CHANGE, function(e:Event):void {
                trace("ime-text:" + field.text);
            });
            addChild(field);
            trace("ime-ready");
        }
    }
}
