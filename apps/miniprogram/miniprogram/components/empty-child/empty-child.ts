import { copy } from "../../vendor/mimi-core/index";

Component({
  data: {
    title: copy.addChildTitle,
    body: copy.addChildBody,
    action: copy.addChild,
  },
  methods: {
    onAdd() {
      this.triggerEvent("add");
    },
  },
});
