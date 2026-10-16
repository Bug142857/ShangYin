$(function () {

    // 头部高度调整
    $(function() {
        const updatePadding = () => {
            const height = $('#navbar').outerHeight() + 10;
            // 头部调整
            $('#masthead .inner').css('padding-top', `${height}px`);
            $('.ai-header-section').css('padding-top', `${height}px`);
            $('#post article').css('margin-top', `${height}px`);

        };

        updatePadding();

        let resizeTimer;
        $(window).on('resize', () => {
            clearTimeout(resizeTimer);
            resizeTimer = setTimeout(updatePadding, 200);
        });
    });

    const getScrollContainer = () => $('html, body');

    function scrollToElement(selector, call) {
        let navbarHeight = 0;
        if ($(window).width() > 767) {
            navbarHeight = $('#navbar').outerHeight() || 0;
        }

        const $el = $(selector).first();
        if (!$el.length) return;

        // 先滚到顶部，等 50ms 让 DOM 重新回流，再滚目标位置
        $('html, body').stop(true).scrollTop(0);

        setTimeout(() => {
            const top = $el.offset().top;
            $('html, body').stop(true).animate({
                scrollTop: top - navbarHeight
            }, 300, function () {
                call()
            });
        }, 50);
    }

    // 关闭app下载
    $('#fix-app-wrapper .fix-close').click(function() {
        $(this).parent().hide()
    })

    // 置顶
    $('.icon-top-unselect').click(() => {
        getScrollContainer().stop(true).animate({ scrollTop: 0 }, 300, function () {
            $('.icon-top-select').removeClass('icon-top-select').addClass('icon-top-unselect');
        });
        $('.icon-top-unselect').removeClass('icon-top-unselect').addClass('icon-top-select');
    });

    // 正文
    $('.icon-content-unselect').click(() => {
        scrollToElement('.post-content > p', () => {
            $('.icon-content-select').removeClass('icon-content-select').addClass('icon-content-unselect');
        });
        $('.icon-content-unselect').removeClass('icon-content-unselect').addClass('icon-content-select');
    });

    // 视频
    $('.icon-video-unselect').click(() => {
        scrollToElement('.post-content > .dplayer', () => {
            $('.icon-video-select').removeClass('icon-video-select').addClass('icon-video-unselect');
        });
        $('.icon-video-unselect').removeClass('icon-video-unselect').addClass('icon-video-select');
    });

    // 评论
    $('.icon-comment-unselect').click(() => {
        scrollToElement('#comments', () => {
            $('.icon-comment-select').removeClass('icon-comment-select').addClass('icon-comment-unselect');
        });
        $('.icon-comment-unselect').removeClass('icon-comment-unselect').addClass('icon-comment-select');
    });

});
//推荐
$(function () {
    const $container = $(".top_picks");
    const $items = $(".top_picks_item");
    const $prevBtn = $(".buttom_left");
    const $nextBtn = $(".buttom_right");

    const itemsPerPage = 2;
    let currentIndex = 0;
    const totalPages = Math.ceil($items.length / itemsPerPage);

    function updateView() {
        const start = currentIndex * itemsPerPage;
        const end = start + itemsPerPage;

        $items.removeClass("active"); // 淡出全部
        $items.slice(start, end).addClass("active"); // 淡入当前两个

        const offset = -(currentIndex * 100);
        $container.css("transform", `translateX(${offset}%)`);


        $prevBtn.toggleClass("disabled", currentIndex === 0);
        $nextBtn.toggleClass("disabled", currentIndex >= totalPages - 1);
    }

    $nextBtn.on("click", function () {
        if (currentIndex < totalPages - 1) {
            currentIndex++;
            updateView();
        }
    });

    $prevBtn.on("click", function () {
        if (currentIndex > 0) {
            currentIndex--;
            updateView();
        }
    });

    updateView();
});